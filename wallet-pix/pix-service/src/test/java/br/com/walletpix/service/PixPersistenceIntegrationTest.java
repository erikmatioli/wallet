package br.com.walletpix.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.walletpix.messages.SpiMessages.Account;
import br.com.walletpix.messages.SpiMessages.Agent;
import br.com.walletpix.messages.SpiMessages.Amount;
import br.com.walletpix.messages.SpiMessages.AppHdr;
import br.com.walletpix.messages.SpiMessages.CdtTrfTxInf;
import br.com.walletpix.messages.SpiMessages.GrpHdr;
import br.com.walletpix.messages.SpiMessages.MsgType;
import br.com.walletpix.messages.SpiMessages.Pacs002;
import br.com.walletpix.messages.SpiMessages.Pacs008;
import br.com.walletpix.messages.SpiMessages.Party;
import br.com.walletpix.messages.SpiMessages.TxInfAndSts;
import br.com.walletpix.messages.SpiMessages.TxStatus;
import br.com.walletpix.service.application.ReceivePixService;
import br.com.walletpix.service.application.SendPixService;
import br.com.walletpix.service.application.SendPixService.InitiateCommand;
import br.com.walletpix.service.application.StatusReportRouter;
import br.com.walletpix.service.application.port.PixPorts.DebitResult;
import br.com.walletpix.service.application.port.PixPorts.PayerAccount;
import br.com.walletpix.service.application.port.PixPorts;
import br.com.walletpix.service.application.port.PixPorts.WalletCore;
import br.com.walletpix.service.domain.HolderCheckResult;
import br.com.walletpix.service.domain.SpiIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The use cases against the real schema (Testcontainers, requires Docker): SQL of the
 * repositories, outbox rows, and duplicate detection under concurrent deliveries - the part the
 * in-memory fakes cannot prove. The bus consumers are off; wallet-core is a fake bean.
 */
@SpringBootTest(properties = {"pix.bus.consumers.enabled=false", "pix.bus.outbox-poll-interval-ms=3600000"})
class PixPersistenceIntegrationTest {

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl());
        registry.add("spring.datasource.username", () -> postgres.getUsername());
        registry.add("spring.datasource.password", () -> postgres.getPassword());
        registry.add("spring.flyway.url", () -> postgres.getJdbcUrl());
        registry.add("spring.flyway.user", () -> postgres.getUsername());
        registry.add("spring.flyway.password", () -> postgres.getPassword());
    }

    /** Fake wallet-core: every holder is valid, credits/debits are idempotent per key and counted. */
    static final class FakeWalletCore implements WalletCore {
        final Map<String, UUID> transactionsByKey = new ConcurrentHashMap<>();
        final AtomicInteger credits = new AtomicInteger();
        final AtomicInteger debits = new AtomicInteger();
        final UUID account = UUID.randomUUID();

        @Override
        public HolderCheckResult checkHolder(String ispb, String branch, String number, String digit, String taxId) {
            return new HolderCheckResult(HolderCheckResult.Outcome.VALID, account);
        }

        @Override
        public UUID credit(String ispb, UUID accountId, long cents, String description, String key,
                           PixPorts.PixRecord pix) {
            return transactionsByKey.computeIfAbsent(key, k -> {
                credits.incrementAndGet();
                return UUID.randomUUID();
            });
        }

        @Override
        public Optional<PayerAccount> findAccount(String ispb, UUID accountId) {
            return Optional.of(new PayerAccount(accountId, "0001", "001000029", "Maria Silva"));
        }

        @Override
        public DebitResult debit(String ispb, UUID accountId, long cents, String description, String key,
                                 PixPorts.PixRecord pix) {
            return new DebitResult.Debited(transactionsByKey.computeIfAbsent(key, k -> {
                debits.incrementAndGet();
                return UUID.randomUUID();
            }), pix.endToEndId());
        }

        @Override
        public UUID reverse(String ispb, UUID debitTransactionId, String description, String reasonCode) {
            return UUID.randomUUID();
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeWalletCore fakeWalletCore() {
            return new FakeWalletCore();
        }
    }

    @Autowired ReceivePixService receive;
    @Autowired SendPixService send;
    @Autowired StatusReportRouter router;
    @Autowired FakeWalletCore walletCore;
    @Autowired JdbcClient jdbc;

    @Test
    void receiveFlowPersistsDecisionOutboxAndCreditsOnceUnderConcurrentSettlements() throws Exception {
        Instant now = Instant.now();
        String e2e = SpiIds.newEndToEndId("99999999", now);
        String orderMsgId = SpiIds.newMessageId("00038166");
        AppHdr orderHdr = new AppHdr("00038166", "12345678", orderMsgId, MsgType.PACS_008, now);
        Pacs008 order = new Pacs008(new GrpHdr(orderMsgId, now), new CdtTrfTxInf(e2e,
                Amount.brl(new BigDecimal("12.34")), new Party("Ext", "11144477735"),
                new Account("0042", "1234565", "TRAN"), new Agent("99999999"), new Party("Maria", "52998224725"),
                new Account("0001", "001000029", "TRAN"), new Agent("12345678"), "teste"));

        receive.onPaymentOrder(orderHdr, order);
        receive.onPaymentOrder(orderHdr, order); // redelivery

        assertThat(status(e2e, "INBOUND")).isEqualTo("ACCEPTED");
        assertThat(outboxCount(MsgType.PACS_002)).isEqualTo(1);

        // The same ACSC delivered 8 times at once (SQS at-least-once + several consumers).
        String acscId = SpiIds.newMessageId("00038166");
        AppHdr acscHdr = new AppHdr("00038166", "12345678", acscId, MsgType.PACS_002, now);
        Pacs002 acsc = new Pacs002(new GrpHdr(acscId, now), orderMsgId, new TxInfAndSts(e2e, TxStatus.ACSC, null, null));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> deliveries = java.util.stream.IntStream.range(0, 8)
                    .<Future<?>>mapToObj(i -> pool.submit(() -> {
                        try {
                            router.onStatusReport(acscHdr, acsc);
                        } catch (RuntimeException concurrentLoser) {
                            // ConcurrentUpdateException for a loser is expected: it would be redelivered
                            // and then be recognised as a duplicate.
                        }
                    }))
                    .toList();
            for (Future<?> f : deliveries) {
                f.get();
            }
        }
        router.onStatusReport(acscHdr, acsc); // the redelivery after the race

        assertThat(status(e2e, "INBOUND")).isEqualTo("CREDITED");
        assertThat(walletCore.credits.get()).as("credit is idempotent by EndToEndId").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE message_type = 'PIX_RECEIVED' AND payload LIKE :e2e")
                .param("e2e", "%" + e2e + "%").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentInitiationsWithTheSameKeyCreateOnePaymentAndOnePacs008() throws Exception {
        String key = "req-" + UUID.randomUUID();
        InitiateCommand command = new InitiateCommand("12345678", key, walletCore.account, "52998224725", "99999999",
                "0042", "1234565", "11144477735", "João", new BigDecimal("5.00"), "teste");
        long pacs008Before = outboxCount(MsgType.PACS_008);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> calls = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(i -> pool.submit(() -> send.initiate(command).payment().endToEndId()))
                    .toList();
            String first = calls.getFirst().get();
            for (Future<String> c : calls) {
                assertThat(c.get()).isEqualTo(first);
            }
        }

        assertThat(jdbc.sql("SELECT count(*) FROM pix_payment WHERE request_id = :k").param("k", key)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(outboxCount(MsgType.PACS_008)).isEqualTo(pacs008Before + 1);
    }

    private String status(String e2e, String direction) {
        return jdbc.sql("SELECT status FROM pix_payment WHERE end_to_end_id = :e2e AND direction = :d")
                .param("e2e", e2e).param("d", direction).query(String.class).single();
    }

    private long outboxCount(String type) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE message_type = :t").param("t", type)
                .query(Long.class).single();
    }
}
