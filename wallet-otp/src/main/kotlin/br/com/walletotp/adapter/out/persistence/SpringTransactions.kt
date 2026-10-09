package br.com.walletotp.adapter.out.persistence

import br.com.walletotp.application.port.Transactions
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactions(transactionManager: PlatformTransactionManager) : Transactions {

    private val template = TransactionTemplate(transactionManager)

    // TransactionTemplate hands back what the work returned (Unit included), so the cast never meets
    // a null the caller did not expect - same as wallet-scheduler.
    @Suppress("UNCHECKED_CAST")
    override fun <T> inTransaction(work: () -> T): T = template.execute { work() } as T
}
