package br.com.walletcore.application.port.in;

import br.com.walletcore.application.port.out.AccountStatusEventRepository.CcsAccountEvent;
import br.com.walletcore.application.port.out.JudicialTransferRepository.PendingTransit;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.account.AccountStatusEvent;
import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BlockId;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Blocks and the closing of an account (ADR-013). Each block is created and released on its own, by whoever holds the
 * scope of its type; the account's policy is rewritten from the active blocks in the same transaction.
 */
public interface AccountControlUseCase {

    int MAX_FEED_PAGE = 500;

    AccountBlock block(BlockCommand command);

    AccountBlock release(ReleaseCommand command);

    List<AccountBlock> blocks(TenantId tenantId, AccountId accountId, boolean activeOnly);

    /**
     * Closes the account for good. Refused while it has money ({@code ACCOUNT_NOT_EMPTY}) or an active judicial block
     * ({@code JUDICIAL_BLOCK_ACTIVE}); its fraud and administrative blocks are released by the closing.
     */
    Account close(CloseCommand command);

    /** Blocks, releases, the opening and the closing of the account, newest first. */
    List<TimelineItem> timeline(TenantId tenantId, AccountId accountId);

    /** Openings and closings after {@code afterSeq}, in order: what wallet-ccs reads (ADR-001 of wallet-ccs). */
    CcsFeed ccsEvents(TenantId tenantId, long afterSeq, int limit);

    /** The judicial transfers to the transit whose TED the treasury has yet to send and record. */
    List<PendingTransit> pendingJudicialTransit(TenantId tenantId);

    /** @param scopes the caller's scopes: the block's type decides which one it needs */
    record BlockCommand(TenantId tenantId, AccountId accountId, AccountBlock.Type type, AccountBlock.Reason reason,
                        String note, AccountBlock.CourtOrder court, Actor actor, Set<String> scopes) {
    }

    record ReleaseCommand(TenantId tenantId, AccountId accountId, BlockId blockId, String note, Actor actor,
                          Set<String> scopes) {
    }

    record CloseCommand(TenantId tenantId, AccountId accountId, AccountStatusEvent.CloseReason reason, String note,
                        Actor actor) {
    }

    enum TimelineKind { OPENED, CLOSED, BLOCKED, RELEASED }

    /** One line of the account's timeline; {@code block} is set for BLOCKED and RELEASED. */
    record TimelineItem(TimelineKind kind, Instant at, Actor actor, String reason, String note, AccountBlock block) {
    }

    /** {@code next} is the {@code after} of the next call: the last seq read, or the one asked for when empty. */
    record CcsFeed(List<CcsAccountEvent> events, long next) {
    }
}
