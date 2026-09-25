package br.com.walletcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Composition root. Lives in the root package so component scanning picks up every adapter
 * module on the classpath (rest, persistence, messaging) plus the wiring in {@code bootstrap}.
 */
@SpringBootApplication
@EnableScheduling
public class WalletCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletCoreApplication.class, args);
    }
}
