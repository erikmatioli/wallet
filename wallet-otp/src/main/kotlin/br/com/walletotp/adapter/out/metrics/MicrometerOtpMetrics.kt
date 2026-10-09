package br.com.walletotp.adapter.out.metrics

import br.com.walletotp.application.port.OtpMetrics
import br.com.walletotp.domain.Purpose
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * otp_challenges_total{purpose,result} and otp_verifications_total{purpose,result} in /actuator/prometheus
 * (ADR-001, decision 7). A growing share of "limited" or "wrong_code" is someone trying codes or flooding.
 */
@Component
class MicrometerOtpMetrics(private val registry: MeterRegistry) : OtpMetrics {

    override fun challenge(purpose: Purpose, result: String) {
        registry.counter("otp.challenges", "purpose", purpose.name, "result", result).increment()
    }

    override fun verification(purpose: Purpose, result: String) {
        registry.counter("otp.verifications", "purpose", purpose.name, "result", result).increment()
    }
}
