package br.com.walletpix.spisim;

import br.com.walletpix.messages.SpiMessages.Account;
import br.com.walletpix.messages.SpiMessages.Agent;
import br.com.walletpix.messages.SpiMessages.Amount;
import br.com.walletpix.messages.SpiMessages.AppHdr;
import br.com.walletpix.messages.SpiMessages.CdtTrfTxInf;
import br.com.walletpix.messages.SpiMessages.Envelope;
import br.com.walletpix.messages.SpiMessages.GrpHdr;
import br.com.walletpix.messages.SpiMessages.MsgType;
import br.com.walletpix.messages.SpiMessages.Pacs002;
import br.com.walletpix.messages.SpiMessages.Pacs004;
import br.com.walletpix.messages.SpiMessages.Pacs008;
import br.com.walletpix.messages.SpiMessages.Party;
import br.com.walletpix.messages.SpiMessages.TxInf;
import br.com.walletpix.messages.SpiMessages.TxInfAndSts;
import br.com.walletpix.messages.SpiMessages.TxStatus;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays the SPI: receives what PSPs send, routes and "settles" it. No real settlement accounts
 * (contas PI) are modelled - settlement is simply the decision to emit ACSC.
 * <ul>
 *   <li>pacs.008 to one of our participants: forwarded to the payee's PSP under a new message id;
 *       its pacs.002 ACSP makes the SPI send ACSC to both sides, its RJCT is passed to the payer.</li>
 *   <li>pacs.008 to the external ISPB: the simulator answers as that PSP - ACSC, or RJCT AC03 when
 *       the amount ends in ,99 (to exercise the refund path).</li>
 *   <li>{@link #injectIncoming} / {@link #injectReturn}: a Pix or a return coming from the external PSP.</li>
 * </ul>
 * State is in memory: restarting the simulator forgets in-flight routes.
 */
@Component
public class SpiSimulator {

    static final String SPI_ISPB = "00038166";

    private static final Logger log = LoggerFactory.getLogger(SpiSimulator.class);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final char[] ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LOG_SIZE = 200;

    /** What the SPI remembers about one Pix in flight. */
    record Route(String endToEndId, String payerIspb, String payeeIspb, String payerMsgId, String payeeMsgId,
                 BigDecimal amount) {
    }

    /** One line of the message log exposed at GET /simulate/messages. */
    public record LoggedMessage(Instant at, String direction, String msgType, String from, String to,
                                String endToEndId, String status, String reason) {
    }

    private final SnsClient sns;
    private final JsonMapper json;
    private final SimulatorProperties props;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Deque<LoggedMessage> messageLog = new ArrayDeque<>();

    SpiSimulator(SnsClient sns, JsonMapper json, SimulatorProperties props) {
        this.sns = sns;
        this.json = json;
        this.props = props;
    }

    // ------------------------------------------------------------------ from PSPs

    void onMessageFromPsp(String body) {
        JsonNode root = json.readTree(body);
        AppHdr hdr = json.treeToValue(root.get("AppHdr"), AppHdr.class);
        JsonNode document = root.get("Document");
        switch (hdr.msgDefIdr()) {
            case MsgType.PACS_008 -> onPaymentOrder(hdr, json.treeToValue(document, Pacs008.class));
            case MsgType.PACS_002 -> onStatusReport(hdr, json.treeToValue(document, Pacs002.class));
            default -> log.warn("ignoring unsupported message {} from {}", hdr.msgDefIdr(), hdr.fr());
        }
    }

    private void onPaymentOrder(AppHdr hdr, Pacs008 order) {
        CdtTrfTxInf t = order.cdtTrfTxInf();
        record("in", hdr, t.endToEndId(), null, null);
        String payee = t.cdtrAgt().ispb();
        Route route = new Route(t.endToEndId(), hdr.fr(), payee, order.grpHdr().msgId(), null, t.intrBkSttlmAmt().amt());
        if (props.participants().contains(payee)) {
            String forwardedId = newMessageId();
            routes.put(t.endToEndId(), new Route(route.endToEndId(), route.payerIspb(), payee, route.payerMsgId(),
                    forwardedId, route.amount()));
            send(payee, MsgType.PACS_008, forwardedId, new Pacs008(new GrpHdr(forwardedId, Instant.now()), t),
                    t.endToEndId(), null, null);
            return;
        }
        routes.put(t.endToEndId(), route);
        boolean reject = t.intrBkSttlmAmt().amt().movePointRight(2).longValue() % 100 == 99;
        if (!payee.equals(props.externalIspb())) {
            statusTo(route.payerIspb(), route.payerMsgId(), t.endToEndId(), TxStatus.RJCT, "AB09",
                    "ISPB " + payee + " não participa do SPI simulado");
        } else if (reject) {
            statusTo(route.payerIspb(), route.payerMsgId(), t.endToEndId(), TxStatus.RJCT, "AC03",
                    "PSP externo simulado rejeitou (valor terminado em ,99)");
        } else {
            statusTo(route.payerIspb(), route.payerMsgId(), t.endToEndId(), TxStatus.ACSC, null, null);
        }
    }

    private void onStatusReport(AppHdr hdr, Pacs002 report) {
        TxInfAndSts s = report.txInfAndSts();
        record("in", hdr, s.orgnlEndToEndId(), s.txSts(), s.rsnCd());
        Route route = routes.get(s.orgnlEndToEndId());
        if (route == null) {
            log.warn("pacs.002 for unknown Pix {}", s.orgnlEndToEndId());
            return;
        }
        boolean payerIsOurs = props.participants().contains(route.payerIspb());
        if (TxStatus.ACSP.equals(s.txSts())) {
            // "Settle": both sides learn it at the same time, as in the SPI.
            statusTo(route.payeeIspb(), route.payeeMsgId(), route.endToEndId(), TxStatus.ACSC, null, null);
            if (payerIsOurs) {
                statusTo(route.payerIspb(), route.payerMsgId(), route.endToEndId(), TxStatus.ACSC, null, null);
            }
        } else if (TxStatus.RJCT.equals(s.txSts()) && payerIsOurs) {
            statusTo(route.payerIspb(), route.payerMsgId(), route.endToEndId(), TxStatus.RJCT, s.rsnCd(), s.addtlInf());
        }
    }

    // ------------------------------------------------------------------ injected by the developer

    /** A Pix from the external PSP to one of our accounts. Returns the EndToEndId. */
    public String injectIncoming(String payeeIspb, String branch, String accountWithDigit, String accountType,
                                 String taxId, String name, BigDecimal amount, String payerName, String description) {
        Instant now = Instant.now();
        String e2e = "E" + props.externalIspb() + MINUTE.format(now) + random(11);
        String msgId = newMessageId();
        routes.put(e2e, new Route(e2e, props.externalIspb(), payeeIspb, null, msgId, amount));
        Pacs008 order = new Pacs008(new GrpHdr(msgId, now), new CdtTrfTxInf(e2e, Amount.brl(amount),
                new Party(payerName == null ? "Pagador Externo" : payerName, "11144477735"),
                new Account("0042", "1234565", "TRAN"), new Agent(props.externalIspb()),
                new Party(name, taxId), new Account(branch, accountWithDigit, accountType == null ? "TRAN" : accountType),
                new Agent(payeeIspb), description));
        send(payeeIspb, MsgType.PACS_008, msgId, order, e2e, null, null);
        return e2e;
    }

    /** The external PSP returns (part of) a Pix one of our participants sent to it. Returns the return id. */
    public String injectReturn(String endToEndId, BigDecimal amount, String reason) {
        Route route = routes.get(endToEndId);
        if (route == null || !props.participants().contains(route.payerIspb())) {
            throw new IllegalArgumentException("no outgoing Pix " + endToEndId + " from one of our participants is known");
        }
        String rtrId = "D" + props.externalIspb() + MINUTE.format(Instant.now()) + random(11);
        String msgId = newMessageId();
        send(route.payerIspb(), MsgType.PACS_004, msgId, new Pacs004(new GrpHdr(msgId, Instant.now()),
                new TxInf(rtrId, endToEndId, Amount.brl(amount), reason == null ? "MD06" : reason)), endToEndId,
                null, reason);
        return rtrId;
    }

    public List<LoggedMessage> recentMessages() {
        synchronized (messageLog) {
            return List.copyOf(messageLog);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void statusTo(String ispb, String originalMsgId, String e2e, String txSts, String reason, String info) {
        String msgId = newMessageId();
        send(ispb, MsgType.PACS_002, msgId, new Pacs002(new GrpHdr(msgId, Instant.now()), originalMsgId,
                new TxInfAndSts(e2e, txSts, reason, info)), e2e, txSts, reason);
    }

    private void send(String toIspb, String msgType, String msgId, Object document, String e2e, String status,
                      String reason) {
        AppHdr hdr = new AppHdr(SPI_ISPB, toIspb, msgId, msgType, Instant.now());
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put("msgType", text(msgType));
        attributes.put("receiverIspb", text(toIspb));
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, Map::put);
        if (carrier.containsKey("traceparent")) {
            attributes.put("traceparent", text(carrier.get("traceparent")));
        }
        sns.publish(b -> b.topicArn(props.spiToPspTopicArn())
                .message(json.writeValueAsString(new Envelope<>(hdr, document)))
                .messageAttributes(attributes));
        record("out", hdr, e2e, status, reason);
    }

    private void record(String direction, AppHdr hdr, String e2e, String status, String reason) {
        log.info("{} {} {} -> {} e2e={} {} {}", direction, hdr.msgDefIdr(), hdr.fr(), hdr.to(), e2e,
                status == null ? "" : status, reason == null ? "" : reason);
        synchronized (messageLog) {
            messageLog.addFirst(new LoggedMessage(Instant.now(), direction, hdr.msgDefIdr(), hdr.fr(), hdr.to(), e2e,
                    status, reason));
            while (messageLog.size() > LOG_SIZE) {
                messageLog.removeLast();
            }
        }
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }

    private static String newMessageId() {
        return "M" + SPI_ISPB + random(23);
    }

    private static String random(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALNUM[RANDOM.nextInt(ALNUM.length)];
        }
        return new String(out);
    }
}
