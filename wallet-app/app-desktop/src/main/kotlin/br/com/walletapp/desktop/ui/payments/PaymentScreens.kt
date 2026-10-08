package br.com.walletapp.desktop.ui.payments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.walletapp.contract.PixReceipt
import br.com.walletapp.desktop.ui.Format

@Composable
fun TransferScreen(viewModel: TransferViewModel, onFinish: () -> Unit) {
    val s by viewModel.state.collectAsState()
    PaymentColumn {
        when (val step = s.step) {
            Step.Form -> {
                Text("Para quem? (conta desta instituição)", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Agência", s.form.branch, Modifier.width(110.dp)) { v -> viewModel.edit { it.copy(branch = v) } }
                    Field("Conta", s.form.number, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(number = v) } }
                    Field("Dígito", s.form.checkDigit, Modifier.width(90.dp)) { v -> viewModel.edit { it.copy(checkDigit = v) } }
                }
                Field("Valor (R$)", s.form.amount) { v -> viewModel.edit { it.copy(amount = v) } }
                Field("Descrição (opcional)", s.form.description) { v -> viewModel.edit { it.copy(description = v) } }
                Error(s.error)
                Busy(s.busy) { Button(onClick = { viewModel.review() }, Modifier.fillMaxWidth()) { Text("Continuar") } }
            }
            is Step.Confirm -> {
                Text("Confira a transferência", style = MaterialTheme.typography.titleMedium)
                Summary(
                    "Valor" to Format.money(step.preview.amountCents),
                    "Para" to step.preview.destination.holderName,
                    "Conta" to step.preview.destination.let { Format.account(it.branch, it.number, it.checkDigit) },
                    "Descrição" to s.form.description.ifBlank { "—" },
                )
                Error(s.error)
                Busy(s.busy) {
                    Button(onClick = { viewModel.confirm() }, Modifier.fillMaxWidth()) { Text("Confirmar transferência") }
                    OutlinedButton(onClick = viewModel::change, Modifier.fillMaxWidth()) { Text("Alterar") }
                }
            }
            is Step.Done -> {
                val r = step.receipt
                Text("Transferência realizada", style = MaterialTheme.typography.titleLarge, color = ok)
                Summary(
                    "Valor" to Format.money(r.amountCents),
                    "Para" to r.destination.holderName,
                    "Conta" to Format.account(r.destination.branch, r.destination.number, r.destination.checkDigit),
                    "Data" to Format.dateTime(r.occurredAt),
                    "Transação" to r.transactionId,
                )
                Button(onClick = { viewModel.reset(); onFinish() }, Modifier.fillMaxWidth()) { Text("Voltar ao início") }
            }
        }
    }
}

@Composable
fun PixScreen(viewModel: PixViewModel, onFinish: () -> Unit) {
    val s by viewModel.state.collectAsState()
    PaymentColumn {
        when (val step = s.step) {
            Step.Form -> {
                Text("Para quem? (conta em outra instituição)", style = MaterialTheme.typography.titleMedium)
                Field("Nome do recebedor", s.form.name) { v -> viewModel.edit { it.copy(name = v) } }
                Field("CPF/CNPJ do recebedor", s.form.taxId) { v -> viewModel.edit { it.copy(taxId = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("ISPB", s.form.ispb, Modifier.width(140.dp)) { v -> viewModel.edit { it.copy(ispb = v) } }
                    Field("Agência", s.form.branch, Modifier.width(110.dp)) { v -> viewModel.edit { it.copy(branch = v) } }
                    Field("Conta com dígito", s.form.account, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(account = v) } }
                }
                Field("Valor (R$)", s.form.amount) { v -> viewModel.edit { it.copy(amount = v) } }
                Field("Mensagem (opcional)", s.form.description) { v -> viewModel.edit { it.copy(description = v) } }
                Error(s.error)
                Button(onClick = viewModel::review, Modifier.fillMaxWidth()) { Text("Continuar") }
            }
            is Step.Confirm -> {
                val p = step.preview.payee
                Text("Confira o Pix", style = MaterialTheme.typography.titleMedium)
                Summary(
                    "Valor" to Format.money(step.preview.amountCents),
                    "Para" to p.name,
                    "CPF/CNPJ" to p.taxId,
                    "Instituição (ISPB)" to p.ispb,
                    "Agência / conta" to "${p.branch} / ${p.accountNumber}",
                )
                Error(s.error)
                Busy(s.busy) {
                    Button(onClick = { viewModel.confirm() }, Modifier.fillMaxWidth()) { Text("Confirmar Pix") }
                    OutlinedButton(onClick = viewModel::change, Modifier.fillMaxWidth()) { Text("Alterar") }
                }
            }
            is Step.Done -> {
                val r = step.receipt
                PixStatusTitle(r)
                Summary(
                    "Valor" to Format.money(r.amountCents),
                    "Para" to r.payeeName,
                    "Instituição (ISPB)" to r.payeeIspb,
                    "EndToEndId" to r.endToEndId,
                )
                Button(onClick = { viewModel.reset(); onFinish() }, Modifier.fillMaxWidth()) { Text("Voltar ao início") }
            }
        }
    }
}

internal val ok = Color(0xFF1B7F3B)

@Composable
private fun PixStatusTitle(r: PixReceipt) {
    when (r.status) {
        "COMPLETED", "RETURNED" -> Text("Pix concluído", style = MaterialTheme.typography.titleLarge, color = ok)
        "REFUNDED" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pix não concluído", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
            Text("${r.reasonMessage ?: "Recusado pelo recebedor."} O valor voltou para a sua conta.")
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.width(22.dp))
            Text("Pix enviado, aguardando a confirmação…", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun PaymentColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
internal fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier)
}

@Composable
internal fun Summary(vararg rows: Pair<String, String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(140.dp))
                    Text(value, fontWeight = if (label == "Valor") FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
internal fun Error(message: String?) {
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

/** The buttons, or a spinner while a request is in flight: nothing can be clicked twice meanwhile. */
@Composable
internal fun Busy(busy: Boolean, buttons: @Composable () -> Unit) {
    if (busy) CircularProgressIndicator() else buttons()
}
