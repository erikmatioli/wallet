package br.com.walletpix.spisim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Development-only stand-in for the BCB's SPI. Never deploy this outside a local environment. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SpiSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpiSimulatorApplication.class, args);
    }
}
