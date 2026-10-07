package br.com.walletscheduler.adapter.out.persistence

import br.com.walletscheduler.application.port.Transactions
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactions(transactionManager: PlatformTransactionManager) : Transactions {

    private val template = TransactionTemplate(transactionManager)

    // The work's result is non-null for every caller except Unit-returning ones, which TransactionTemplate
    // also hands back as Unit - so the cast never meets a null it did not expect.
    @Suppress("UNCHECKED_CAST")
    override fun <T> inTransaction(work: () -> T): T = template.execute { work() } as T
}
