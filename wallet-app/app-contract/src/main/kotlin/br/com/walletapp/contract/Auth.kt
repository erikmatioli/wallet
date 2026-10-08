package br.com.walletapp.contract

import kotlinx.serialization.Serializable

/** `POST /app/v1/signup`. The CPF may come formatted; app-api keeps only the digits. */
@Serializable
data class SignupRequest(val cpf: String, val name: String, val password: String)

/** `POST /app/v1/login`. */
@Serializable
data class LoginRequest(val cpf: String, val password: String)

/**
 * What signup and login answer: the customer's token, valid for [expiresInSeconds]. The desktop keeps
 * it only in memory (ADR-001, decision 5) and sends it as `Authorization: Bearer`.
 */
@Serializable
data class Session(val token: String, val expiresInSeconds: Long, val customerName: String)
