package br.com.walletcore.bootstrap;

import br.com.walletcore.application.port.in.AuditLedgerUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.in.ProvisionTenantUseCase;
import br.com.walletcore.application.port.in.ListAccountsUseCase;
import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.CustomerRepository;
import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.application.port.out.MetricsRecorder;
import br.com.walletcore.application.port.out.OutboxRepository;
import br.com.walletcore.application.port.out.SecretHasher;
import br.com.walletcore.application.port.out.TenantRepository;
import br.com.walletcore.application.port.out.TransactionJournal;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.application.service.AuditLedgerService;
import br.com.walletcore.application.service.MoveMoneyService;
import br.com.walletcore.application.service.OnboardCustomerService;
import br.com.walletcore.application.service.ProvisionTenantService;
import br.com.walletcore.application.service.ListAccountsService;
import br.com.walletcore.application.service.QueryAccountService;
import br.com.walletcore.application.service.SettlementRouter;
import java.time.Clock;
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
    SettlementRouter settlementRouter(AccountRepository accounts) {
        return new SettlementRouter(accounts);
    }

    @Bean
    OnboardCustomerUseCase onboardCustomerUseCase(TransactionRunner tx, TenantRepository tenants,
                                                   CustomerRepository customers, AccountRepository accounts,
                                                   OutboxRepository outbox, MetricsRecorder metrics, Clock clock) {
        return new OnboardCustomerService(tx, tenants, customers, accounts, outbox, metrics, clock);
    }

    @Bean
    MoveMoneyUseCase moveMoneyUseCase(TransactionRunner tx, AccountRepository accounts, LedgerRepository ledger,
                                      TransactionJournal journal, OutboxRepository outbox,
                                      SettlementRouter settlementRouter, MetricsRecorder metrics, Clock clock) {
        return new MoveMoneyService(tx, accounts, ledger, journal, outbox, settlementRouter, metrics, clock);
    }

    @Bean
    QueryAccountUseCase queryAccountUseCase(TransactionRunner tx, AccountRepository accounts, LedgerRepository ledger) {
        return new QueryAccountService(tx, accounts, ledger);
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
                                                  AccountRepository accounts, SecretHasher hasher, Clock clock) {
        return new ProvisionTenantService(tx, tenants, accounts, hasher, clock);
    }
}
