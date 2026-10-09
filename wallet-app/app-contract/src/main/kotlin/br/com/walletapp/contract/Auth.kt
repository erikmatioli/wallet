package br.com.walletapp.contract

import kotlinx.serialization.Serializable

// Signup and login by a one-time code sent to the customer's email (ADR-002): no password anywhere. Each
// one is two calls - "start" sends the code, "confirm" checks it - and the CPF may come formatted.

/** `POST /app/v1/signup/start`: the code goes to [email], which this proves the customer owns. */
@Serializable
data class SignupStartRequest(val cpf: String, val name: String, val email: String)

/** `POST /app/v1/signup/confirm`: the same data as the start, plus the code that arrived. */
@Serializable
data class SignupConfirmRequest(val challengeId: String, val code: String, val cpf: String, val name: String,
                                val email: String)

/** `POST /app/v1/login/start`: only the CPF; the code goes to the email of the account. */
@Serializable
data class LoginStartRequest(val cpf: String)

/** `POST /app/v1/login/confirm`. */
@Serializable
data class LoginConfirmRequest(val challengeId: String, val cpf: String, val code: String)

/**
 * What a "start" answers. [message] is written for the customer; for a login it is the same whether the
 * CPF has an account or not. [resendAfterSeconds]: when the desktop may offer "Reenviar código".
 */
@Serializable
data class CodeSent(val challengeId: String, val message: String, val resendAfterSeconds: Int = 60)

/**
 * What a confirmed signup or login answers: the customer's token, valid for [expiresInSeconds]. The desktop
 * keeps it only in memory (ADR-001, decision 5) and sends it as `Authorization: Bearer`.
 */
@Serializable
data class Session(val token: String, val expiresInSeconds: Long, val customerName: String)
