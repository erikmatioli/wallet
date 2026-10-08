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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** A narrow column centred in the window, for the two forms. */
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
        OutlinedTextField(s.cpf, viewModel::onCpf, label = { Text("CPF") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            s.password, viewModel::onPassword, label = { Text("Senha") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (s.canSubmit) viewModel.submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { viewModel.submit() }, enabled = s.canSubmit, modifier = Modifier.fillMaxWidth()) {
            if (s.busy) CircularProgressIndicator(Modifier.widthIn(max = 18.dp)) else Text("Entrar")
        }
        TextButton(onClick = onSignup, modifier = Modifier.fillMaxWidth()) { Text("Ainda não tenho conta: abrir conta") }
    }
}

@Composable
fun SignupScreen(viewModel: SignupViewModel, onBack: () -> Unit) {
    val s by viewModel.state.collectAsState()
    FormColumn {
        Text("Abrir conta", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(s.name, viewModel::onName, label = { Text("Nome completo") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.cpf, viewModel::onCpf, label = { Text("CPF") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.password, viewModel::onPassword, label = { Text("Senha (8+ caracteres, letras e números)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.confirmation, viewModel::onConfirmation, label = { Text("Repita a senha") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { viewModel.submit() }, enabled = s.canSubmit, modifier = Modifier.fillMaxWidth()) {
            if (s.busy) CircularProgressIndicator(Modifier.widthIn(max = 18.dp)) else Text("Abrir minha conta")
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Já tenho conta: entrar") }
    }
}
