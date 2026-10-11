package br.com.walletcore.bootstrap;

import br.com.walletcore.application.port.in.AuditLedgerUseCase;
import br.com.walletcore.application.port.in.BalanceHistoryUseCase;
import br.com.walletcore.application.port.in.CustomerContactUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.in.ProvisionTenantUseCase;
import br.com.walletcore.application.port.in.ListAccountsUseCase;
import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.application.port.in.TreasuryUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.BackingAccountRepository;
import br.com.walletcore.application.port.out.CustomerRepository;
import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.application.port.out.MetricsRecorder;
import br.com.walletcore.application.port.out.OutboxRepository;
import br.com.walletcore.application.port.out.PixDetailRepository;
import br.com.walletcore.application.port.out.SecretHasher;
import br.com.walletcore.application.port.out.TenantRepository;
import br.com.walletcore.application.port.out.TransactionJournal;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.application.service.AuditLedgerService;
import br.com.walletcore.application.service.BalanceHistoryService;
import br.com.walletcore.application.service.CustomerContactService;
import br.com.walletcore.application.service.MoveMoneyService;
import br.com.walletcore.application.service.OnboardCustomerService;
import br.com.walletcore.application.service.ProvisionTenantService;
import br.com.walletcore.application.service.ListAccountsService;
import br.com.walletcore.application.service.QueryAccountService;
import br.com.walletcore.application.service.BackingRouter;
import br.com.walletcore.application.service.ClosingBalances;
import br.com.walletcore.application.service.TreasuryService;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free application services to their ports. The application module has
 * no Spring dependency at all; this is the only place where use cases meet the container.
 */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BackingRouter backingRouter(BackingAccountRepository backing) {
        return new BackingRouter(backing);
    }

    /** ADR-012: closing balances from the ledger. The grace must exceed the longest posting transaction. */
    @Bean
    ClosingBalances closingBalances(LedgerRepository ledger, Clock clock,
                                    @Value("${wallet.balance.day-close-grace:1m}") Duration dayCloseGrace) {
        return new ClosingBalances(ledger, clock, dayCloseGrace);
    }

    @Bean
    OnboardCustomerUseCase onboardCustomerUseCase(TransactionRunner tx, TenantRepository tenants,
                                                   CustomerRepository customers, AccountRepository accounts,
                                                   OutboxRepository outbox, MetricsRecorder metrics, Clock clock) {
        return new OnboardCustomerService(tx, tenants, customers, accounts, outbox, metrics, clock);
    }

    @Bean
    CustomerContactUseCase customerContactUseCase(TransactionRunner tx, CustomerRepository customers) {
        return new CustomerContactService(tx, customers);
    }

    @Bean
    MoveMoneyUseCase moveMoneyUseCase(TransactionRunner tx, AccountRepository accounts, LedgerRepository ledger,
                                      TransactionJournal journal, OutboxRepository outbox, PixDetailRepository pixDetails,
                                      BackingAccountRepository backing, BackingRouter backingRouter,
                                      MetricsRecorder metrics, Clock clock) {
        return new MoveMoneyService(tx, accounts, ledger, journal, outbox, pixDetails, backing, backingRouter, metrics,
                clock);
    }

    @Bean
    TreasuryUseCase treasuryUseCase(TransactionRunner tx, AccountRepository accounts, BackingAccountRepository backing,
                                    LedgerRepository ledger, BackingRouter backingRouter, ClosingBalances closings,
                                    Clock clock) {
        return new TreasuryService(tx, accounts, backing, ledger, backingRouter, closings, clock);
    }

    @Bean
    QueryAccountUseCase queryAccountUseCase(TransactionRunner tx, AccountRepository accounts, LedgerRepository ledger,
                                            PixDetailRepository pixDetails) {
        return new QueryAccountService(tx, accounts, ledger, pixDetails);
    }

    @Bean
    BalanceHistoryUseCase balanceHistoryUseCase(TransactionRunner tx, AccountRepository accounts,
                                                ClosingBalances closings) {
        return new BalanceHistoryService(tx, accounts, closings);
    }

    @Bean
    ListAccountsUseCase listAccountsUseCase(TransactionRunner tx, AccountRepository accounts) {
        return new ListAccountsService(tx, accounts);
    }

    @Bean
    AuditLedgerUseCase auditLedgerUseCase(TransactionRunner tx, AccountRepository accounts, LedgerRepository ledger,
                                          MetricsRecorder metrics) {
        return new AuditLedgerService(tx, accounts, ledger, metrics);
    }

    @Bean
    ProvisionTenantUseCase provisionTenantUseCase(TransactionRunner tx, TenantRepository tenants,
                                                  BackingAccountRepository backing, SecretHasher hasher, Clock clock) {
        return new ProvisionTenantService(tx, tenants, backing, hasher, clock);
    }
}
