package br.com.walletpix.messages;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Messages exchanged with the SPI, as JSON whose field names and nesting mirror the ISO 20022
 * tags of the SPI message catalog (AppHdr / Document, GrpHdr, CdtTrfTxInf, EndToEndId, ...).
 *
 * <p>Deliberate simplifications versus the real catalog (documented in the README): one
 * transaction per message (the SPI never batches Pix), party ids flattened to a single
 * {@code Id} (CPF or CNPJ, digits only) instead of {@code PrvtId}/{@code OrgId}, agents
 * identified directly by ISPB, and an account {@code Id} that is the account number with its
 * check digit appended - as in the SPI, where {@code CdtrAcct.Id.Othr.Id} carries both.
 */
public final class SpiMessages {

    private SpiMessages() {
    }

    /** Values of {@link AppHdr#msgDefIdr()}. */
    public static final class MsgType {
        public static final String PACS_008 = "pacs.008";
        public static final String PACS_002 = "pacs.002";
        public static final String PACS_004 = "pacs.004";

        private MsgType() {
        }
    }

    /** Values of {@link TxInfAndSts#txSts()}. */
    public static final class TxStatus {
        /** Accepted by the receiving PSP, waiting for settlement (receiver -> SPI). */
        public static final String ACSP = "ACSP";
        /** Settled by the SPI (SPI -> both PSPs). */
        public static final String ACSC = "ACSC";
        /** Rejected, with a reason code in {@link TxInfAndSts#rsnCd()}. */
        public static final String RJCT = "RJCT";

        private TxStatus() {
        }
    }

    /** Business application header: who sends to whom, and which message this is. */
    public record AppHdr(
            @JsonProperty("Fr") String fr,
            @JsonProperty("To") String to,
            @JsonProperty("BizMsgIdr") String bizMsgIdr,
            @JsonProperty("MsgDefIdr") String msgDefIdr,
            @JsonProperty("CreDt") Instant creDt) {
    }

    /** What goes on the bus: header plus one of {@link Pacs008}, {@link Pacs002}, {@link Pacs004}. */
    public record Envelope<T>(
            @JsonProperty("AppHdr") AppHdr appHdr,
            @JsonProperty("Document") T document) {
    }

    public record GrpHdr(
            @JsonProperty("MsgId") String msgId,
            @JsonProperty("CreDtTm") Instant creDtTm) {
    }

    public record Amount(
            @JsonProperty("Amt") BigDecimal amt,
            @JsonProperty("Ccy") String ccy) {

        public static Amount brl(BigDecimal value) {
            return new Amount(value, "BRL");
        }
    }

    /** A person or company: name and CPF/CNPJ (digits only). */
    public record Party(
            @JsonProperty("Nm") String nm,
            @JsonProperty("Id") String id) {
    }

    /** Branch ({@code Issr}), account number with check digit ({@code Id}) and type ({@code Tp}, e.g. TRAN). */
    public record Account(
            @JsonProperty("Issr") String issr,
            @JsonProperty("Id") String id,
            @JsonProperty("Tp") String tp) {
    }

    /** A participant institution, identified by its ISPB. */
    public record Agent(@JsonProperty("Ispb") String ispb) {
    }

    // ------------------------------------------------------------------- pacs.008 (payment order)

    public record Pacs008(
            @JsonProperty("GrpHdr") GrpHdr grpHdr,
            @JsonProperty("CdtTrfTxInf") CdtTrfTxInf cdtTrfTxInf) {
    }

    public record CdtTrfTxInf(
            @JsonProperty("EndToEndId") String endToEndId,
            @JsonProperty("IntrBkSttlmAmt") Amount intrBkSttlmAmt,
            @JsonProperty("Dbtr") Party dbtr,
            @JsonProperty("DbtrAcct") Account dbtrAcct,
            @JsonProperty("DbtrAgt") Agent dbtrAgt,
            @JsonProperty("Cdtr") Party cdtr,
            @JsonProperty("CdtrAcct") Account cdtrAcct,
            @JsonProperty("CdtrAgt") Agent cdtrAgt,
            @JsonProperty("RmtInf") String rmtInf) {
    }

    // ------------------------------------------------------------------- pacs.002 (status report)

    public record Pacs002(
            @JsonProperty("GrpHdr") GrpHdr grpHdr,
            @JsonProperty("OrgnlMsgId") String orgnlMsgId,
            @JsonProperty("TxInfAndSts") TxInfAndSts txInfAndSts) {
    }

    public record TxInfAndSts(
            @JsonProperty("OrgnlEndToEndId") String orgnlEndToEndId,
            @JsonProperty("TxSts") String txSts,
            @JsonProperty("RsnCd") String rsnCd,
            @JsonProperty("AddtlInf") String addtlInf) {
    }

    // ------------------------------------------------------------------- pacs.004 (payment return)

    public record Pacs004(
            @JsonProperty("GrpHdr") GrpHdr grpHdr,
            @JsonProperty("TxInf") TxInf txInf) {
    }

    public record TxInf(
            @JsonProperty("RtrId") String rtrId,
            @JsonProperty("OrgnlEndToEndId") String orgnlEndToEndId,
            @JsonProperty("RtrdIntrBkSttlmAmt") Amount rtrdIntrBkSttlmAmt,
            @JsonProperty("RtrRsnCd") String rtrRsnCd) {
    }
}
