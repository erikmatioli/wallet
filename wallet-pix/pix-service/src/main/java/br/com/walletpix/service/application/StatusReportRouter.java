package br.com.walletpix.service.application;

import br.com.walletpix.messages.SpiMessages.AppHdr;
import br.com.walletpix.messages.SpiMessages.MsgType;
import br.com.walletpix.messages.SpiMessages.Pacs002;
import br.com.walletpix.service.application.MessageFailures.RetryLater;
import br.com.walletpix.service.application.port.PixPorts.InboundMessageLog;
import br.com.walletpix.service.application.port.PixPorts.PixMetrics;
import br.com.walletpix.service.application.port.PixPorts.PixPaymentRepository;
import br.com.walletpix.service.domain.PixPayment;

/**
 * A pacs.002 from the SPI can be about a Pix we received or one we sent - and, when both sides
 * are our tenants, about both. {@code OrgnlMsgId} disambiguates: it is the id of the pacs.008
 * the recipient of this report saw, which differs per side (the SPI forwards the order to the
 * payee's PSP under a new message id).
 */
public final class StatusReportRouter {

    private final PixPaymentRepository payments;
    private final InboundMessageLog messageLog;
    private final ReceivePixService receive;
    private final SendPixService send;
    private final PixMetrics metrics;

    public StatusReportRouter(PixPaymentRepository payments, InboundMessageLog messageLog, ReceivePixService receive,
                              SendPixService send, PixMetrics metrics) {
        this.payments = payments;
        this.messageLog = messageLog;
        this.receive = receive;
        this.send = send;
        this.metrics = metrics;
    }

    public void onStatusReport(AppHdr hdr, Pacs002 report) {
        if (messageLog.alreadyProcessed(hdr.bizMsgIdr())) {
            metrics.inboundMessage(MsgType.PACS_002, "duplicate");
            return;
        }
        // Unknown original message: most likely the payment's own commit isn't visible yet, or
        // the report overtook it. Retry; if it never resolves, the redrive policy moves it to the DLQ.
        PixPayment payment = payments.findByPacs008MsgId(report.orgnlMsgId())
                .orElseThrow(() -> new RetryLater("pacs.002 for unknown pacs.008 " + report.orgnlMsgId()));
        if (payment.direction() == PixPayment.Direction.INBOUND) {
            receive.onStatusReport(hdr, payment, report.txInfAndSts());
        } else {
            send.onStatusReport(hdr, payment, report.txInfAndSts());
        }
    }
}
