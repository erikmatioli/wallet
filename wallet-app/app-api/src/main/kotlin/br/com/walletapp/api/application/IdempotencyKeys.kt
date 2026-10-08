package br.com.walletapp.api.application

import br.com.walletapp.api.domain.LoginId
import br.com.walletapp.api.domain.ValidationException
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The Idempotency-Key app-api sends on, for every money movement and schedule.
 *
 * wallet-core, wallet-pix and wallet-scheduler keep idempotency keys per tenant, and every customer of the
 * app is the same tenant: without the customer in the key, two customers' keys could collide and one would
 * get the other's payment back as a "replay". So the key is derived from the customer and the desktop's
 * key: the same pair always gives the same key (a retry stays a retry), and it fits wallet-pix's 64
 * characters without carrying the login id to the other services.
 */
object IdempotencyKeys {

    fun forCustomer(login: LoginId, desktopKey: String?): String {
        val k = desktopKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("IDEMPOTENCY_KEY_REQUIRED", "Requisição sem Idempotency-Key.")
        if (k.length > 64) throw ValidationException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key longa demais.")
        val digest = MessageDigest.getInstance("SHA-256").digest("$login:$k".toByteArray())
        return "app-" + HexFormat.of().formatHex(digest).take(40)
    }
}
