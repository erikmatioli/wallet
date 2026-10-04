package br.com.walletpix.spisim;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Developer controls: inject traffic that would come from another PSP, and inspect what crossed the "SPI". */
@RestController
@RequestMapping("/simulate")
class SimulatorController {

    private final SpiSimulator simulator;

    SimulatorController(SpiSimulator simulator) {
        this.simulator = simulator;
    }

    /**
     * @param accountNumber account number with its check digit appended (e.g. 00100002 + 9 = "001000029")
     * @param accountType   defaults to TRAN; anything else makes the receiving PSP answer AC14
     */
    record IncomingPix(String payeeIspb, String branch, String accountNumber, String accountType, String taxId,
                       String name, BigDecimal amount, String payerName, String description) {
    }

    record ReturnPix(String endToEndId, BigDecimal amount, String reason) {
    }

    @PostMapping("/incoming")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> incoming(@RequestBody IncomingPix r) {
        String e2e = simulator.injectIncoming(r.payeeIspb(), r.branch(), r.accountNumber(), r.accountType(), r.taxId(),
                r.name(), r.amount(), r.payerName(), r.description());
        return Map.of("endToEndId", e2e);
    }

    @PostMapping("/return")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> returnPix(@RequestBody ReturnPix r) {
        return Map.of("returnId", simulator.injectReturn(r.endToEndId(), r.amount(), r.reason()));
    }

    @GetMapping("/messages")
    List<SpiSimulator.LoggedMessage> messages() {
        return simulator.recentMessages();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
