package br.com.walletapp.desktop.ui.schedules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.walletapp.contract.Schedule
import br.com.walletapp.desktop.ui.Format
import br.com.walletapp.desktop.ui.payments.Busy
import br.com.walletapp.desktop.ui.payments.Error
import br.com.walletapp.desktop.ui.payments.Field
import br.com.walletapp.desktop.ui.payments.PaymentColumn
import br.com.walletapp.desktop.ui.payments.Step
import br.com.walletapp.desktop.ui.payments.Summary
import br.com.walletapp.desktop.ui.payments.ok

@Composable
fun SchedulesScreen(viewModel: SchedulesViewModel, onNew: () -> Unit) {
    val s by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Pagamentos que esta conta vai fazer", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onNew) { Text("Novo agendamento") }
        }
        Error(s.error.takeIf { s.selected == null })
        when {
            s.loading && s.schedules.isEmpty() -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.schedules.isEmpty() -> Text("Nenhum agendamento.")
            else -> LazyColumn {
                items(s.schedules, key = { it.id }) { ScheduleRow(it, onClick = { viewModel.open(it) }) }
            }
        }
    }
    s.selected?.let { ScheduleDetail(it, s.cancelling, s.error, onCancel = { viewModel.cancel() }, onClose = viewModel::close) }
}

@Composable
private fun ScheduleRow(s: Schedule, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Format.date(s.executeOn), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
        Column(Modifier.weight(1f)) {
            Text(if (s.type == "PIX") "Pix" else "Transferência")
            Text(s.payee, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(Format.money(s.amountCents))
        Text(Format.scheduleStatus(s), style = MaterialTheme.typography.bodySmall, color = statusColor(s), modifier = Modifier.width(150.dp))
    }
    HorizontalDivider()
}

@Composable
private fun statusColor(s: Schedule) = when {
    s.execution.status == "EXECUTED" -> ok
    s.execution.status == "FAILED" || s.status == "CANCELLED" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Everything about one schedule, from what the list brought - including why it was not paid. */
@Composable
private fun ScheduleDetail(s: Schedule, cancelling: Boolean, error: String?, onCancel: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
        dismissButton = {
            if (s.canCancel) {
                if (cancelling) CircularProgressIndicator(Modifier.width(22.dp))
                else TextButton(onClick = onCancel) { Text("Cancelar agendamento", color = MaterialTheme.colorScheme.error) }
            }
        },
        title = { Text("${if (s.type == "PIX") "Pix agendado" else "Transferência agendada"}  ${Format.money(s.amountCents)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Detail("Situação", Format.scheduleStatus(s))
                Detail("Data", Format.date(s.executeOn))
                Detail("Para", s.payee)
                Detail("Destino", s.payeeDetail)
                s.description?.let { Detail("Descrição", it) }
                s.execution.failureMessage?.let { Detail(if (s.execution.status == "FAILED") "Motivo" else "Última recusa", it) }
                s.execution.endToEndId?.let { Detail("EndToEndId", it) }
                if (s.attempts.isNotEmpty()) {
                    Text("Tentativas", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    s.attempts.forEach { a ->
                        val result = when (a.outcome) {
                            "EXECUTED" -> "Pago"
                            "REFUSED" -> "Recusado"
                            else -> "Em andamento"
                        }
                        Text("${Format.shortDate(a.startedAt)}  $result${a.reasonMessage?.let { " — $it" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Error(error)
            }
        },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun NewScheduleScreen(viewModel: NewScheduleViewModel, onFinish: () -> Unit) {
    val s by viewModel.state.collectAsState()
    PaymentColumn {
        when (val step = s.step) {
            Step.Form -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !s.form.pix, onClick = { viewModel.edit { it.copy(pix = false) } }, label = { Text("Transferência") })
                    FilterChip(selected = s.form.pix, onClick = { viewModel.edit { it.copy(pix = true) } }, label = { Text("Pix") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Data (dd/mm/aaaa)", s.form.date, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(date = v) } }
                    Field("Valor (R$)", s.form.amount, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(amount = v) } }
                }
                if (!s.form.pix) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Agência", s.form.branch, Modifier.width(110.dp)) { v -> viewModel.edit { it.copy(branch = v) } }
                        Field("Conta", s.form.number, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(number = v) } }
                        Field("Dígito", s.form.checkDigit, Modifier.width(90.dp)) { v -> viewModel.edit { it.copy(checkDigit = v) } }
                    }
                } else {
                    Field("Nome do recebedor", s.form.name) { v -> viewModel.edit { it.copy(name = v) } }
                    Field("CPF/CNPJ do recebedor", s.form.taxId) { v -> viewModel.edit { it.copy(taxId = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("ISPB", s.form.ispb, Modifier.width(140.dp)) { v -> viewModel.edit { it.copy(ispb = v) } }
                        Field("Agência", s.form.pixBranch, Modifier.width(110.dp)) { v -> viewModel.edit { it.copy(pixBranch = v) } }
                        Field("Conta com dígito", s.form.account, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(account = v) } }
                    }
                }
                Field("Descrição (opcional)", s.form.description) { v -> viewModel.edit { it.copy(description = v) } }
                Text("O pagamento é tentado às 06:00, 12:00 e 18:00 (Brasília) do dia. Dá para cancelar até a véspera.",
                    style = MaterialTheme.typography.bodySmall)
                Error(s.error)
                Button(onClick = viewModel::review, Modifier.fillMaxWidth()) { Text("Continuar") }
            }
            is Step.Confirm -> {
                val r = step.preview
                Text("Confira o agendamento", style = MaterialTheme.typography.titleMedium)
                Summary(
                    "Valor" to Format.money(r.amountCents),
                    "Data" to Format.date(r.executeOn),
                    "Tipo" to if (r.pix != null) "Pix" else "Transferência",
                    "Para" to (r.pix?.let { "${it.name} (ISPB ${it.ispb})" }
                        ?: r.transfer!!.let { Format.account(it.branch, it.number, it.checkDigit) }),
                    "Descrição" to (r.description ?: "—"),
                )
                Error(s.error)
                Busy(s.busy) {
                    Button(onClick = { viewModel.confirm() }, Modifier.fillMaxWidth()) { Text("Confirmar agendamento") }
                    OutlinedButton(onClick = viewModel::change, Modifier.fillMaxWidth()) { Text("Alterar") }
                }
            }
            is Step.Done -> {
                val created = step.receipt
                Text("Agendado para ${Format.date(created.executeOn)}", style = MaterialTheme.typography.titleLarge, color = ok)
                Summary("Valor" to Format.money(created.amountCents), "Para" to created.payee, "Destino" to created.payeeDetail)
                Button(onClick = { viewModel.reset(); onFinish() }, Modifier.fillMaxWidth()) { Text("Ver meus agendamentos") }
            }
        }
    }
}
