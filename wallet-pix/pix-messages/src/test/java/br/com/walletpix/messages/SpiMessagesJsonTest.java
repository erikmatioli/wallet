package br.com.walletpix.messages;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.walletpix.messages.SpiMessages.Account;
import br.com.walletpix.messages.SpiMessages.Agent;
import br.com.walletpix.messages.SpiMessages.Amount;
import br.com.walletpix.messages.SpiMessages.AppHdr;
import br.com.walletpix.messages.SpiMessages.CdtTrfTxInf;
import br.com.walletpix.messages.SpiMessages.Envelope;
import br.com.walletpix.messages.SpiMessages.GrpHdr;
import br.com.walletpix.messages.SpiMessages.Pacs008;
import br.com.walletpix.messages.SpiMessages.Party;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The wire format is the contract between the Pix service and the SPI: field names must stay ISO 20022 tags. */
class SpiMessagesJsonTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void pacs008UsesIsoTagsAndRoundTrips() {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        var envelope = new Envelope<>(new AppHdr("12345678", "00038166", "M1", SpiMessages.MsgType.PACS_008, now),
                new Pacs008(new GrpHdr("M1", now), new CdtTrfTxInf("E1", Amount.brl(new BigDecimal("10.50")),
                        new Party("A", "52998224725"), new Account("0001", "001000029", "TRAN"), new Agent("12345678"),
                        new Party("B", "11144477735"), new Account("0042", "1234565", "TRAN"), new Agent("99999999"),
                        "aluguel")));

        String text = json.writeValueAsString(envelope);
        JsonNode tree = json.readTree(text);

        assertThat(tree.path("AppHdr").path("MsgDefIdr").asString()).isEqualTo("pacs.008");
        assertThat(tree.path("AppHdr").path("CreDt").asString()).isEqualTo("2026-10-04T12:00:00Z");
        JsonNode tx = tree.path("Document").path("CdtTrfTxInf");
        assertThat(tx.path("EndToEndId").asString()).isEqualTo("E1");
        assertThat(tx.path("IntrBkSttlmAmt").path("Ccy").asString()).isEqualTo("BRL");
        assertThat(tx.path("CdtrAcct").path("Issr").asString()).isEqualTo("0042");
        assertThat(tx.path("CdtrAgt").path("Ispb").asString()).isEqualTo("99999999");

        Envelope<Pacs008> back = json.readValue(text, new TypeReference<>() {
        });
        assertThat(back).isEqualTo(envelope);
    }
}
