package br.com.walletpix.service.adapter.in.sqs;

import br.com.walletpix.messages.SpiMessages.AppHdr;
import br.com.walletpix.messages.SpiMessages.MsgType;
import br.com.walletpix.messages.SpiMessages.Pacs002;
import br.com.walletpix.messages.SpiMessages.Pacs004;
import br.com.walletpix.messages.SpiMessages.Pacs008;
import br.com.walletpix.service.adapter.bus.SqsQueueConsumer;
import br.com.walletpix.service.application.MessageFailures.PermanentFailure;
import br.com.walletpix.service.application.ReceivePixService;
import br.com.walletpix.service.application.SendPixService;
import br.com.walletpix.service.application.StatusReportRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Messages from the SPI: reads the header, then routes the document by its message type. */
public final class SpiInboundHandler implements SqsQueueConsumer.Handler {

    private static final Logger log = LoggerFactory.getLogger(SpiInboundHandler.class);

    private final JsonMapper json;
    private final ReceivePixService receive;
    private final SendPixService send;
    private final StatusReportRouter statusReports;

    public SpiInboundHandler(JsonMapper json, ReceivePixService receive, SendPixService send,
                             StatusReportRouter statusReports) {
        this.json = json;
        this.receive = receive;
        this.send = send;
        this.statusReports = statusReports;
    }

    @Override
    public void handle(String body) {
        JsonNode root;
        AppHdr hdr;
        try {
            root = json.readTree(body);
            hdr = json.treeToValue(root.required("AppHdr"), AppHdr.class);
        } catch (JacksonException e) {
            throw new PermanentFailure("unreadable SPI message: " + e.getOriginalMessage());
        }
        if (hdr.msgDefIdr() == null || hdr.bizMsgIdr() == null) {
            throw new PermanentFailure("SPI message without MsgDefIdr/BizMsgIdr");
        }
        log.debug("SPI message {} {} from {} to {}", hdr.msgDefIdr(), hdr.bizMsgIdr(), hdr.fr(), hdr.to());
        JsonNode document = root.path("Document");
        switch (hdr.msgDefIdr()) {
            case MsgType.PACS_008 -> receive.onPaymentOrder(hdr, read(document, Pacs008.class));
            case MsgType.PACS_002 -> statusReports.onStatusReport(hdr, read(document, Pacs002.class));
            case MsgType.PACS_004 -> send.onReturn(hdr, read(document, Pacs004.class));
            default -> throw new PermanentFailure("unsupported SPI message type " + hdr.msgDefIdr());
        }
    }

    private <T> T read(JsonNode document, Class<T> type) {
        try {
            return json.treeToValue(document, type);
        } catch (JacksonException e) {
            throw new PermanentFailure("invalid " + type.getSimpleName() + ": " + e.getOriginalMessage());
        }
    }
}
