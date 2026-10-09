package br.com.walletotp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WalletOtpApplication

fun main(args: Array<String>) {
    runApplication<WalletOtpApplication>(*args)
}
