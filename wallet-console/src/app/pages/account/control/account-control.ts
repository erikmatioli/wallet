import { Component, computed, effect, input, output, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import { WalletApiService } from '../../../core/wallet-api.service';
import { extractErrorMessage } from '../../../core/http-error.util';
import {
  AccountBlock,
  AccountDetailResponse,
  BlockReason,
  BlockRequest,
  BlockType,
  CloseReason,
  TimelineItem,
} from '../../../core/models';
import {
  ADMINISTRATIVE_REASONS,
  BLOCK_EFFECTS,
  BLOCK_REASON_LABELS,
  BLOCK_TYPE_LABELS,
  CLOSE_REASON_LABELS,
  activeJudicialBlocks,
  blockScope,
  rememberOperator,
  rememberedOperator,
  timelineLabel,
  timelineReason,
} from '../../../core/account-control';
import { ProblemBanner } from '../../../shared/problem-banner/problem-banner';

type Panel = 'FRAUD' | 'ADMINISTRATIVE' | 'JUDICIAL' | 'JUDICIAL_TRANSFER' | 'CLOSE' | null;

/** A check of the closing list (ADR-013, item 5): passed, failed, or not known (the service did not answer). */
interface Check {
  label: string;
  ok: boolean | null;
  detail: string;
}

/**
 * Blocks, the judicial transfer and the closing of an account (ADR-013 of the core). Each action shows only to the
 * scope that may do it; the operator's name goes with every one, as the console has no operator login yet.
 */
@Component({
  selector: 'app-account-control',
  imports: [FormsModule, DatePipe, DecimalPipe, ProblemBanner],
  templateUrl: './account-control.html',
  styleUrl: './account-control.scss',
})
export class AccountControl {
  readonly account = input.required<AccountDetailResponse>();
  /** The account changed: the page reloads it and its statement. */
  readonly changed = output<string>();

  protected readonly typeLabels = BLOCK_TYPE_LABELS;
  protected readonly effects = BLOCK_EFFECTS;
  protected readonly reasonLabels = BLOCK_REASON_LABELS;
  protected readonly administrativeReasons = ADMINISTRATIVE_REASONS;
  protected readonly closeReasonLabels = CLOSE_REASON_LABELS;
  protected readonly closeReasons = Object.keys(CLOSE_REASON_LABELS) as CloseReason[];
  protected readonly timelineLabel = timelineLabel;
  protected readonly timelineReason = timelineReason;

  readonly canBlock: boolean;
  readonly canJudicial: boolean;
  readonly canClose: boolean;

  readonly panel = signal<Panel>(null);
  readonly releasing = signal<string | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly timeline = signal<TimelineItem[] | null>(null);
  readonly checks = signal<Check[]>([]);

  readonly closed = computed(() => this.account().status === 'CLOSED');
  readonly judicialBlocks = computed(() => activeJudicialBlocks(this.account().blocks));
  readonly anyAction = computed(() => !this.closed() && (this.canBlock || this.canJudicial || this.canClose));

  operator = rememberedOperator();
  note = '';
  reason: BlockReason = 'INSTITUTION_DECISION';
  process = '';
  protocol = '';
  releaseNote = '';
  // Judicial transfer. The Idempotency-Key lives as long as the form: a retry after a timeout cannot pay twice.
  transferBlockId = '';
  transferAmount = 0;
  transferTo: 'JUDICIAL_TRANSIT' | 'ACCOUNT' = 'JUDICIAL_TRANSIT';
  transferBranch = '';
  transferNumber = '';
  transferCheckDigit = '';
  private transferKey = crypto.randomUUID();
  closeReason: CloseReason = 'CUSTOMER_REQUEST';

  constructor(
    private readonly api: WalletApiService,
    auth: AuthService,
  ) {
    this.canBlock = auth.hasScope('accounts:block');
    this.canJudicial = auth.hasScope('accounts:judicial');
    this.canClose = auth.hasScope('accounts:close');
    // Another account (a link in the statement) or the same one after an action: the history is read again.
    effect(() => this.loadTimeline(this.account().id));
  }

  /** The types of the active blocks, once each, for the line that says what each one still lets through. */
  readonly activeTypes = computed(() => [...new Set(this.account().blocks.map((b) => b.type))]);

  /** "Ordem judicial · processo 0001234-… · bloqueio total" */
  blockDetail(b: AccountBlock): string {
    return [BLOCK_REASON_LABELS[b.reason], b.court ? `processo ${b.court.process}` : null, b.court?.protocol, b.note]
      .filter(Boolean)
      .join(' · ');
  }

  timelineDetail(i: TimelineItem): string {
    return [timelineReason(i), i.court ? `processo ${i.court.process}` : null, i.note].filter(Boolean).join(' · ') || '—';
  }

  /** The fee block is never released here: wallet-fees releases it when the open fees are paid (ADR-014). */
  canRelease(block: AccountBlock): boolean {
    if (block.type === 'FEE_PENDING') return false;
    return block.active && !this.closed() && (block.type === 'JUDICIAL' ? this.canJudicial : this.canBlock);
  }

  open(panel: Panel): void {
    this.error.set(null);
    this.releasing.set(null);
    this.note = '';
    this.process = '';
    this.protocol = '';
    this.reason = 'INSTITUTION_DECISION';
    this.closeReason = 'CUSTOMER_REQUEST';
    if (panel === 'JUDICIAL_TRANSFER') {
      this.transferBlockId = this.judicialBlocks()[0]?.id ?? '';
      this.transferAmount = this.account().balance;
      this.transferTo = 'JUDICIAL_TRANSIT';
      this.transferKey = crypto.randomUUID();
    }
    if (panel === 'CLOSE') {
      this.loadChecks();
    }
    this.panel.set(this.panel() === panel ? null : panel);
  }

  submitBlock(type: BlockType): void {
    const request: BlockRequest = {
      type,
      reason: type === 'FRAUD' ? 'FRAUD_SUSPICION' : type === 'JUDICIAL' ? 'COURT_ORDER' : this.reason,
      operator: this.operator,
      note: this.note.trim() || undefined,
      court: type === 'JUDICIAL' ? { process: this.process.trim(), protocol: this.protocol.trim() || undefined } : undefined,
    };
    this.run(this.api.blockAccount(this.account().id, request), `${BLOCK_TYPE_LABELS[type]}: conta bloqueada.`);
  }

  startRelease(block: AccountBlock): void {
    this.panel.set(null);
    this.error.set(null);
    this.releaseNote = '';
    this.releasing.set(this.releasing() === block.id ? null : block.id);
  }

  submitRelease(block: AccountBlock): void {
    this.run(
      this.api.releaseBlock(this.account().id, block.id, this.operator, this.releaseNote.trim()),
      `${BLOCK_TYPE_LABELS[block.type]}: bloqueio liberado.`,
    );
  }

  submitJudicialTransfer(): void {
    const destination =
      this.transferTo === 'JUDICIAL_TRANSIT'
        ? ({ kind: 'JUDICIAL_TRANSIT' } as const)
        : ({
            kind: 'ACCOUNT',
            branch: this.transferBranch.trim(),
            number: this.transferNumber.trim(),
            checkDigit: this.transferCheckDigit.trim(),
          } as const);
    this.run(
      this.api.judicialTransfer(
        this.account().id,
        {
          blockId: this.transferBlockId,
          amount: this.transferAmount,
          destination,
          operator: this.operator,
          note: this.note.trim() || undefined,
        },
        this.transferKey,
      ),
      this.transferTo === 'JUDICIAL_TRANSIT'
        ? 'Transferência judicial feita. A TED ao depósito judicial fica pendente na tesouraria.'
        : 'Transferência judicial feita.',
    );
  }

  submitClose(): void {
    this.run(
      this.api.closeAccount(this.account().id, this.closeReason, this.operator, this.note.trim() || undefined),
      'Conta encerrada.',
    );
  }

  private loadTimeline(accountId: string): void {
    this.timeline.set(null);
    this.api.accountTimeline(accountId).subscribe({
      next: (r) => this.timeline.set(r.items),
      error: (err) => this.error.set(extractErrorMessage(err)),
    });
  }

  /** The closing list: the core's checks from the account itself, the scheduler's from its API. */
  private loadChecks(): void {
    const acc = this.account();
    const base: Check[] = [
      {
        label: 'Saldo zero',
        ok: acc.balance === 0,
        detail: acc.balance === 0 ? '' : 'Devolva o dinheiro ao cliente (Pix ou saque) antes de encerrar.',
      },
      {
        label: 'Nenhum bloqueio judicial ativo',
        ok: this.judicialBlocks().length === 0,
        detail: this.judicialBlocks().length === 0 ? '' : 'A ordem precisa ser cumprida ou liberada antes.',
      },
      {
        label: 'Nenhum Pix enviado aguardando o SPI',
        ok: null,
        detail: 'Confira no wallet-pix: um estorno que chegue depois do encerramento é recusado.',
      },
    ];
    this.checks.set([...base, { label: 'Nenhum agendamento pendente', ok: null, detail: 'Conferindo...' }]);
    this.api.listSchedules(acc.id).subscribe({
      next: (schedules) => {
        const pending = schedules.filter((s) => s.status === 'ACTIVE').length;
        this.checks.set([
          ...base,
          {
            label: 'Nenhum agendamento pendente',
            ok: pending === 0,
            detail: pending === 0 ? '' : `${pending} agendamento(s) ativo(s): cancele na seção Agendamentos.`,
          },
        ]);
      },
      error: () =>
        this.checks.set([
          ...base,
          { label: 'Nenhum agendamento pendente', ok: null, detail: 'O wallet-scheduler não respondeu: confira à mão.' },
        ]),
    });
  }

  private run(call: Observable<unknown>, message: string): void {
    if (!this.operator.trim()) {
      this.error.set('Informe o nome do operador.');
      return;
    }
    rememberOperator(this.operator);
    this.error.set(null);
    this.busy.set(true);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.panel.set(null);
        this.releasing.set(null);
        this.changed.emit(message);
      },
      error: (err) => {
        this.error.set(extractErrorMessage(err));
        this.busy.set(false);
      },
    });
  }

  protected scopeOf(type: BlockType): string {
    return blockScope(type);
  }
}
