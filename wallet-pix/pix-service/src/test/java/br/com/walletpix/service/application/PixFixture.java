package br.com.walletpix.service.application;

import br.com.walletpix.messages.PixEvents.PixEvent;
import br.com.walletpix.messages.SpiMessages.Envelope;
import br.com.walletpix.service.application.port.PixPorts;
import br.com.walletpix.service.application.port.PixPorts.ConcurrentUpdateException;
import br.com.walletpix.service.domain.HolderCheckResult;
import br.com.walletpix.service.domain.PixPayment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** In-memory ports for fast use-case tests. Transactions roll back the outbox on failure, like the real one. */
final class PixFixture {

    static final String OUR_ISPB = "12345678";
    static final String OTHER_OUR_ISPB = "87654321";
    static final String EXTERNAL_ISPB = "99999999";

    final Clock clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);
    final Map<String, PixPayment> paymentsByKey = new HashMap<>();
    final Set<String> processedMessages = new HashSet<>();
    final List<Envelope<?>> sentToSpi = new ArrayList<>();
    final List<PixEvent> events = new ArrayList<>();

    /** Holder check answer per "branch/account/taxId"; anything else is ACCOUNT_NOT_FOUND. */
    final Map<String, HolderCheckResult> holders = new HashMap<>();
    /** Every credit call (including idempotent repeats), and the transaction id per key. */
    final List<String> creditCalls = new ArrayList<>();
    final Map<String, UUID> creditsByKey = new HashMap<>();
    final Map<String, Long> creditedCentsByKey = new HashMap<>();
    int holderCheckCalls = 0;
    boolean walletCoreDown = false;

    /** Payer side: accounts wallet-core knows, their balance, every debit and reversal. */
    final Map<UUID, PixPorts.PayerAccount> walletAccounts = new HashMap<>();
    final Map<UUID, Long> balances = new HashMap<>();
    final Map<String, UUID> debitsByKey = new HashMap<>();
    final Map<UUID, Long> debitAmounts = new HashMap<>();
    final Map<UUID, UUID> debitAccounts = new HashMap<>();
    final Map<UUID, UUID> reversalsByDebit = new HashMap<>();
    int debitCalls = 0;
    final List<PixPorts.PaymentPolicy> policies = new ArrayList<>();

    final PixPorts.PixPaymentRepository payments = new PixPorts.PixPaymentRepository() {
        @Override
        public Optional<PixPayment> find(String endToEndId, PixPayment.Direction direction) {
            return Optional.ofNullable(paymentsByKey.get(endToEndId + "/" + direction));
        }

        @Override
        public Optional<PixPayment> findByPacs008MsgId(String msgId) {
            return paymentsByKey.values().stream().filter(p -> p.pacs008MsgId().equals(msgId)).findFirst();
        }

        @Override
        public Optional<PixPayment> findByRequestId(String requestId) {
            return paymentsByKey.values().stream().filter(p -> requestId.equals(p.requestId())).findFirst();
        }

        @Override
        public void insert(PixPayment p) {
            paymentsByKey.put(p.endToEndId() + "/" + p.direction(), p);
        }

        @Override
        public void update(PixPayment p) {
            PixPayment stored = paymentsByKey.get(p.endToEndId() + "/" + p.direction());
            if (stored == null || stored.version() != p.version()) {
                throw new ConcurrentUpdateException("stale " + p.endToEndId());
            }
            paymentsByKey.put(p.endToEndId() + "/" + p.direction(), withVersion(p, p.version() + 1));
        }
    };

    final PixPorts.InboundMessageLog messageLog = new PixPorts.InboundMessageLog() {
        @Override
        public boolean alreadyProcessed(String key) {
            return processedMessages.contains(key);
        }

        @Override
        public boolean markProcessed(String key, String type) {
            return processedMessages.add(key);
        }
    };

    final PixPorts.OutboundMessages outbound = new PixPorts.OutboundMessages() {
        @Override
        public void sendToSpi(Envelope<?> message) {
            sentToSpi.add(message);
        }

        @Override
        public void publishEvent(PixEvent event) {
            events.add(event);
        }
    };

    final PixPorts.WalletCore walletCore = new PixPorts.WalletCore() {
        @Override
        public HolderCheckResult checkHolder(String ispb, String branch, String number, String digit, String taxId) {
            holderCheckCalls++;
            if (walletCoreDown) {
                throw new MessageFailures.RetryLater("wallet-core down");
            }
            // wallet-core accepts the document with or without mask (TaxId.parse); so does the fake.
            return holders.getOrDefault(branch + "/" + number + digit + "/" + taxId.replaceAll("\\D", ""),
                    new HolderCheckResult(HolderCheckResult.Outcome.ACCOUNT_NOT_FOUND, null));
        }

        @Override
        public UUID credit(String ispb, UUID accountId, long cents, String description, String key) {
            if (walletCoreDown) {
                throw new MessageFailures.RetryLater("wallet-core down");
            }
            creditCalls.add(key);
            creditedCentsByKey.putIfAbsent(key, cents);
            return creditsByKey.computeIfAbsent(key, k -> UUID.randomUUID());
        }

        @Override
        public Optional<PixPorts.PayerAccount> findAccount(String ispb, UUID accountId) {
            return Optional.ofNullable(walletAccounts.get(accountId));
        }

        @Override
        public PixPorts.DebitResult debit(String ispb, UUID accountId, long cents, String description, String key) {
            debitCalls++;
            if (walletCoreDown) {
                throw new MessageFailures.RetryLater("wallet-core down");
            }
            UUID existing = debitsByKey.get(key);
            if (existing != null) {
                return new PixPorts.DebitResult.Debited(existing); // idempotent replay, like wallet-core
            }
            long balance = balances.getOrDefault(accountId, 0L);
            if (balance < cents) {
                return new PixPorts.DebitResult.Refused("INSUFFICIENT_FUNDS", "insufficient funds");
            }
            balances.put(accountId, balance - cents);
            UUID id = UUID.randomUUID();
            debitsByKey.put(key, id);
            debitAmounts.put(id, cents);
            debitAccounts.put(id, accountId);
            return new PixPorts.DebitResult.Debited(id);
        }

        @Override
        public UUID reverse(String ispb, UUID debitTransactionId, String description) {
            if (walletCoreDown) {
                throw new MessageFailures.RetryLater("wallet-core down");
            }
            return reversalsByDebit.computeIfAbsent(debitTransactionId, d -> {
                balances.merge(debitAccounts.get(d), debitAmounts.get(d), Long::sum);
                return UUID.randomUUID();
            });
        }
    };

    /** Snapshot-and-restore of the in-memory state, so a failing unit of work leaves no trace. */
    final PixPorts.Transactions tx = new PixPorts.Transactions() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            var paymentsBefore = new HashMap<>(paymentsByKey);
            var processedBefore = new HashSet<>(processedMessages);
            int sentBefore = sentToSpi.size();
            int eventsBefore = events.size();
            try {
                return work.get();
            } catch (RuntimeException e) {
                paymentsByKey.clear();
                paymentsByKey.putAll(paymentsBefore);
                processedMessages.clear();
                processedMessages.addAll(processedBefore);
                sentToSpi.subList(sentBefore, sentToSpi.size()).clear();
                events.subList(eventsBefore, events.size()).clear();
                throw e;
            }
        }
    };

    final PixPorts.Participants participants = ispb -> OUR_ISPB.equals(ispb) || OTHER_OUR_ISPB.equals(ispb);

    final PixPorts.PixMetrics metrics = new PixPorts.PixMetrics() {
        @Override
        public void inboundMessage(String type, String result) {
        }

        @Override
        public void payment(PixPayment payment) {
        }
    };

    final ReceivePixService receive = new ReceivePixService(walletCore, payments, messageLog, outbound, tx,
            participants, metrics, clock);
    final SendPixService send = new SendPixService(walletCore, payments, messageLog, outbound, tx, participants,
            policies, metrics, clock);
    final StatusReportRouter router = new StatusReportRouter(payments, messageLog, receive, send, metrics);

    void registerHolder(String branch, String accountWithDigit, String taxId, UUID accountId) {
        holders.put(branch + "/" + accountWithDigit + "/" + taxId,
                new HolderCheckResult(HolderCheckResult.Outcome.VALID, accountId));
    }

    void registerHolderOutcome(String branch, String accountWithDigit, String taxId, HolderCheckResult.Outcome outcome) {
        holders.put(branch + "/" + accountWithDigit + "/" + taxId, new HolderCheckResult(outcome, null));
    }

    /** A payer account in wallet-core with a balance and a valid holder (for the payer check). */
    UUID payerAccount(String branch, String accountWithDigit, String taxId, long balanceCents) {
        UUID id = UUID.randomUUID();
        walletAccounts.put(id, new PixPorts.PayerAccount(id, branch, accountWithDigit, "Maria Silva"));
        balances.put(id, balanceCents);
        registerHolder(branch, accountWithDigit, taxId, id);
        return id;
    }

    PixPayment stored(String endToEndId, PixPayment.Direction direction) {
        return payments.find(endToEndId, direction).orElseThrow();
    }

    private static PixPayment withVersion(PixPayment p, long version) {
        return new PixPayment(p.id(), p.endToEndId(), p.direction(), p.status(), p.ispb(), p.counterpartIspb(),
                p.pacs008MsgId(), p.amountCents(), p.payer(), p.payee(), p.walletAccountId(), p.debitTransactionId(), p.requestId(),
                p.reasonCode(), p.description(), p.walletTransactionId(), version, p.createdAt(), p.updatedAt());
    }
}
