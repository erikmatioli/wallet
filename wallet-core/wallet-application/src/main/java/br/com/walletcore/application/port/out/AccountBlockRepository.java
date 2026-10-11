package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BlockId;
import br.com.walletcore.domain.shared.TenantId;
import java.util.List;
import java.util.Optional;

/** The blocks of the accounts (ADR-013). A block is never deleted: releasing it only records the release. */
public interface AccountBlockRepository {

    void insert(AccountBlock block);

    Optional<AccountBlock> findById(TenantId tenantId, BlockId id);

    /** The account's blocks, newest first; only the active ones when {@code activeOnly}. */
    List<AccountBlock> findByAccount(TenantId tenantId, AccountId accountId, boolean activeOnly);

    /** Writes the release of a block that was active: the only change a block row ever takes. */
    void markReleased(AccountBlock released);
}
