package br.com.walletapp.api.adapter.`in`.rest

import br.com.walletapp.api.config.AppProperties
import br.com.walletapp.contract.AppInfo
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The desktop's first call: proves it reaches this service and speaks the same contract. AppInfo is a
 * @Serializable class from app-contract, so Spring writes it with kotlinx.serialization - the same
 * library the desktop reads it with.
 */
@RestController
class InfoController(@Value("\${spring.application.name}") private val name: String, private val props: AppProperties) {

    /** Also which tenant this instance serves: the desktop shows it, so one can tell the apps apart. */
    @GetMapping("/app/v1/info")
    fun info() = AppInfo(name, javaClass.`package`.implementationVersion ?: "dev", props.walletCore.clientId)
}
