package br.com.walletapp.desktop.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** A narrow column centred in the window, for the forms. */
@Composable
private fun FormColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun Busy(busy: Boolean, label: String) {
    if (busy) CircularProgressIndicator(Modifier.widthIn(max = 18.dp)) else Text(label)
}

/**
 * The code that arrived by email (ADR-002), the same for login and signup. "Reenviar código" waits for the
 * countdown; [back] goes back to change the CPF or the data.
 */
@Composable
private fun CodeEntry(step: CodeStep, busy: Boolean, error: String?, onCode: (String) -> Unit, confirm: () -> Unit,
                      resend: () -> Unit, backLabel: String, back: () -> Unit) {
    Text(step.sentMessage, style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        step.code, onCode, label = { Text("Código de 6 dígitos") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (step.canConfirm && !busy) confirm() }),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = confirm, enabled = step.canConfirm && !busy, modifier = Modifier.fillMaxWidth()) { Busy(busy, "Confirmar") }
    TextButton(onClick = resend, enabled = step.canResend && !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (step.canResend) "Reenviar código" else "Reenviar código em ${step.resendIn} s")
    }
    TextButton(onClick = back, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(backLabel) }
}

/**
 * [notice]: why the customer is here again (session expired), shown above the form.
 * The screen only draws the ViewModel's state and forwards what is typed: each keystroke updates the
 * state, and the new state redraws the field.
 */
@Composable
fun LoginScreen(viewModel: LoginViewModel, notice: String?, onSignup: () -> Unit) {
    val s by viewModel.state.collectAsState()
    FormColumn {
        Text("Wallet", style = MaterialTheme.typography.headlineLarge)
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val step = s.step
        if (step == null) {
            OutlinedTextField(
                s.cpf, viewModel::onCpf, label = { Text("CPF") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (s.canSend) viewModel.sendCode() }),
                modifier = Modifier.fillMaxWidth(),
            )
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { viewModel.sendCode() }, enabled = s.canSend, modifier = Modifier.fillMaxWidth()) {
                Busy(s.busy, "Receber código por e-mail")
            }
            TextButton(onClick = onSignup, modifier = Modifier.fillMaxWidth()) { Text("Ainda não tenho conta: abrir conta") }
        } else {
            Text("CPF ${s.cpf}", style = MaterialTheme.typography.titleSmall)
            CodeEntry(step, s.busy, s.error, viewModel::onCode, { viewModel.confirm() }, { viewModel.sendCode() },
                "Trocar CPF", viewModel::changeCpf)
        }
    }
}

@Composable
fun SignupScreen(viewModel: SignupViewModel, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    FormColumn {
        Text("Abrir conta", style = MaterialTheme.typography.headlineMedium)
        val step = s.step
        if (step == null) {
            OutlinedTextField(s.name, viewModel::onName, label = { Text("Nome completo") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(s.cpf, viewModel::onCpf, label = { Text("CPF") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                s.email, viewModel::onEmail, label = { Text("E-mail") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (s.canSend) viewModel.sendCode() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Vamos mandar um código para este e-mail. É por ele que você vai entrar no app.",
                style = MaterialTheme.typography.bodySmall)
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { viewModel.sendCode() }, enabled = s.canSend, modifier = Modifier.fillMaxWidth()) {
                Busy(s.busy, "Continuar")
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Já tenho conta: entrar") }
        } else {
            CodeEntry(step, s.busy, s.error, viewModel::onCode, { viewModel.confirm() }, { viewModel.sendCode() },
                "Corrigir os dados", viewModel::changeData)
        }
    }
}
