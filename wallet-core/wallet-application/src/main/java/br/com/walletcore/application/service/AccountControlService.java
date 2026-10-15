package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.AccountControlUseCase;
import br.com.walletcore.application.port.out.AccountBlockRepository;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.AccountStatusEventRepository;
import br.com.walletcore.application.port.out.JudicialTransferRepository;
import br.com.walletcore.application.port.out.JudicialTransferRepository.PendingTransit;
import br.com.walletcore.application.port.out.OutboxRepository;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.account.AccountPolicy;
import br.com.walletcore.domain.account.AccountStatusEvent;
import br.com.walletcore.domain.event.AccountBlockReleased;
import br.com.walletcore.domain.event.AccountBlocked;
import br.com.walletcore.domain.event.AccountClosed;
import br.com.walletcore.domain.event.DomainEvent;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.ForbiddenException;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Blocks and the closing of an account (ADR-013). Every change locks the account row first, so two blocks created at
 * once cannot both compute the policy from a list that misses the other; and a movement waiting on that same row reads
 * the new policy when it gets it.
 */
public final class AccountControlService implements AccountControlUseCase {

    static final String CLOSING_NOTE = "Encerramento da conta";

    private final TransactionRunner tx;
    private final AccountRepository accounts;
    private final AccountBlockRepository blocks;
    private final AccountStatusEventRepository events;
    private final JudicialTransferRepository judicial;
    private final OutboxRepository outbox;
    private final Clock clock;

    public AccountControlService(TransactionRunner tx, AccountRepository accounts, AccountBlockRepository blocks,
                                 AccountStatusEventRepository events, JudicialTransferRepository judicial,
                                 OutboxRepository outbox, Clock clock) {
        this.tx = tx;
        this.accounts = accounts;
        this.blocks = blocks;
        this.events = events;
        this.judicial = judicial;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public AccountBlock block(BlockCommand c) {
        requireScope(c.scopes(), c.type());
        AccountBlock block = AccountBlock.create(c.tenantId(), c.accountId(), c.type(), c.reason(), c.note(), c.court(),
                c.actor(), clock.instant());
        return tx.inTransaction(c.tenantId(), () -> {
            Account account = lockCustomer(c.tenantId(), c.accountId());
            if (!account.isActive()) {
                throw new BusinessRuleException("ACCOUNT_CLOSED", "a closed account cannot be blocked");
            }
            blocks.insert(block);
            refreshPolicy(c.tenantId(), c.accountId());
            outbox.enqueue(List.of(AccountBlocked.of(block)));
            return block;
        });
    }

    @Override
    public AccountBlock release(ReleaseCommand c) {
        if (c.blockId() == null) {
            throw new ValidationException("INVALID_BLOCK_ID", "block id is required");
        }
        return tx.inTransaction(c.tenantId(), () -> {
            lockCustomer(c.tenantId(), c.accountId());
            AccountBlock block = blocks.findById(c.tenantId(), c.blockId())
                    .filter(b -> b.accountId().equals(c.accountId()))
                    .orElseThrow(() -> new NotFoundException("BLOCK_NOT_FOUND", "block not found on this account"));
            // Who may lift a block is who may create one of its type: the fraud team never lifts the court's.
            requireScope(c.scopes(), block.type());
            AccountBlock released = block.release(c.actor(), c.note(), clock.instant());
            blocks.markReleased(released);
            boolean stillBlocked = !refreshPolicy(c.tenantId(), c.accountId()).isEmpty();
            outbox.enqueue(List.of(AccountBlockReleased.of(released, stillBlocked)));
            return released;
        });
    }

    @Override
    public List<AccountBlock> blocks(TenantId tenantId, AccountId accountId, boolean activeOnly) {
        return tx.readOnly(tenantId, () -> {
            loadCustomer(tenantId, accountId);
            return blocks.findByAccount(tenantId, accountId, activeOnly);
        });
    }

    @Override
    public Account close(CloseCommand c) {
        if (c.reason() == null) {
            throw new ValidationException("INVALID_CLOSE_REASON", "reason is required");
        }
        if (c.reason() == AccountStatusEvent.CloseReason.OTHER && (c.note() == null || c.note().isBlank())) {
            throw new ValidationException("NOTE_REQUIRED", "a closing for another reason needs a note");
        }
        String note = c.note() == null || c.note().isBlank() ? null : c.note().strip();
        if (note != null && note.length() > 280) {
            throw new ValidationException("INVALID_NOTE", "note must have at most 280 characters");
        }
        return tx.inTransaction(c.tenantId(), () -> {
            // The row lock also waits for any movement in flight: the balance read here is the final one.
            Account account = lockCustomer(c.tenantId(), c.accountId());
            if (!account.isActive()) {
                throw new BusinessRuleException("ACCOUNT_ALREADY_CLOSED", "this account is already closed");
            }
            if (account.balance().cents() != 0) {
                throw new BusinessRuleException("ACCOUNT_NOT_EMPTY",
                        "the account still has " + account.balance().toDecimal() + "; return it to the customer first");
            }
            List<AccountBlock> active = blocks.findByAccount(c.tenantId(), c.accountId(), true);
            if (active.stream().anyMatch(b -> b.type() == AccountBlock.Type.JUDICIAL)) {
                throw new BusinessRuleException("JUDICIAL_BLOCK_ACTIVE",
                        "the court's order must be carried out or released before the account closes");
            }
            Instant now = clock.instant();
            List<DomainEvent> published = new ArrayList<>();
            for (AccountBlock b : active) {
                AccountBlock released = b.release(c.actor(), CLOSING_NOTE, now);
                blocks.markReleased(released);
                published.add(AccountBlockReleased.of(released, false));
            }
            accounts.updatePolicy(c.tenantId(), c.accountId(), AccountPolicy.OPEN);
            accounts.close(c.tenantId(), c.accountId(), now);
            events.append(c.tenantId(), new AccountStatusEvent(0, c.accountId(), Account.Status.ACTIVE,
                    Account.Status.CLOSED, now, c.actor(), c.reason(), note));
            published.add(AccountClosed.of(c.tenantId(), c.accountId(), c.reason().name(), now));
            outbox.enqueue(published);
            return loadCustomer(c.tenantId(), c.accountId());
        });
    }

    @Override
    public List<TimelineItem> timeline(TenantId tenantId, AccountId accountId) {
        return tx.readOnly(tenantId, () -> {
            loadCustomer(tenantId, accountId);
            List<TimelineItem> items = new ArrayList<>();
            for (AccountStatusEvent e : events.findByAccount(tenantId, accountId)) {
                items.add(new TimelineItem(e.isOpening() ? TimelineKind.OPENED : TimelineKind.CLOSED, e.occurredAt(),
                        e.actor(), e.reason() == null ? null : e.reason().name(), e.note(), null));
            }
            for (AccountBlock b : blocks.findByAccount(tenantId, accountId, false)) {
                items.add(new TimelineItem(TimelineKind.BLOCKED, b.createdAt(), b.createdBy(), b.reason().name(),
                        b.note(), b));
                if (b.release() != null) {
                    items.add(new TimelineItem(TimelineKind.RELEASED, b.release().at(), b.release().by(), null,
                            b.release().note(), b));
                }
            }
            // Newest first; at the same instant (the blocks a closing releases), the closing goes on top.
            items.sort(Comparator.comparing(TimelineItem::at).thenComparing(i -> i.kind() == TimelineKind.CLOSED)
                    .reversed());
            return items;
        });
    }

    @Override
    public CcsFeed ccsEvents(TenantId tenantId, long afterSeq, int limit) {
        if (afterSeq < 0) {
            throw new ValidationException("INVALID_CURSOR", "after must be zero or positive");
        }
        int pageSize = Math.min(Math.max(limit, 1), MAX_FEED_PAGE);
        return tx.readOnly(tenantId, () -> {
            var page = events.findAfter(tenantId, afterSeq, pageSize);
            return new CcsFeed(page, page.isEmpty() ? afterSeq : page.get(page.size() - 1).seq());
        });
    }

    @Override
    public List<PendingTransit> pendingJudicialTransit(TenantId tenantId) {
        return tx.readOnly(tenantId, () -> judicial.findPendingTransit(tenantId));
    }

    /** Rewrites the policy from the active blocks and answers their types. */
    private List<AccountBlock.Type> refreshPolicy(TenantId tenantId, AccountId accountId) {
        List<AccountBlock.Type> active = blocks.findByAccount(tenantId, accountId, true).stream()
                .map(AccountBlock::type).toList();
        accounts.updatePolicy(tenantId, accountId, AccountPolicy.of(active));
        return active;
    }

    private static void requireScope(Set<String> scopes, AccountBlock.Type type) {
        if (type == null) {
            throw new ValidationException("INVALID_BLOCK_TYPE", "type must be FRAUD, ADMINISTRATIVE or JUDICIAL");
        }
        if (scopes == null || !scopes.contains(type.scope())) {
            throw new ForbiddenException("SCOPE_REQUIRED", "a " + type + " block needs the scope " + type.scope());
        }
    }

    private Account lockCustomer(TenantId tenantId, AccountId accountId) {
        return accounts.lockForUpdate(tenantId, accountId)
                .filter(a -> a.kind() == Account.Kind.CUSTOMER) // internal accounts are never exposed
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "account not found"));
    }

    private Account loadCustomer(TenantId tenantId, AccountId accountId) {
        return accounts.findById(tenantId, accountId)
                .filter(a -> a.kind() == Account.Kind.CUSTOMER)
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "account not found"));
    }
}
