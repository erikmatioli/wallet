package br.com.walletapp.contract

import kotlinx.serialization.Serializable

/**
 * `GET /app/v1/info`: which app-api the desktop is talking to. Open, no token. [tenant]: the fintech whose
 * app this is - each app-api instance serves exactly one (ADR-001, decision 1).
 */
@Serializable
data class AppInfo(val name: String, val version: String, val tenant: String = "")

/**
 * Every error of the app's API: a stable [code] and a [message] the desktop can show as it is.
 * [retryAfterSeconds] only for TOO_MANY_REQUESTS: how long until another code may be asked for.
 */
@Serializable
data class AppError(val code: String, val message: String, val retryAfterSeconds: Long? = null)
