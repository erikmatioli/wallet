package br.com.walletscheduler

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WalletSchedulerApplication

fun main(args: Array<String>) {
    runApplication<WalletSchedulerApplication>(*args)
}
