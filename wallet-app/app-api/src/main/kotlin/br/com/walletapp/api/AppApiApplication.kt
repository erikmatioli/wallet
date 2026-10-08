package br.com.walletapp.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AppApiApplication

fun main(args: Array<String>) {
    runApplication<AppApiApplication>(*args)
}
