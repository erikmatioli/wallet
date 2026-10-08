package br.com.walletapp.desktop.ui.account

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.walletapp.contract.StatementEntry
import br.com.walletapp.desktop.ui.Format

private val credit = Color(0xFF1B7F3B)
private val debit = Color(0xFFB42318)

@Composable
fun HomeScreen(viewModel: HomeViewModel, onStatement: () -> Unit, onTransfer: () -> Unit, onPix: () -> Unit,
               onSchedules: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }

    when (val s = state) {
        HomeViewModel.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is HomeViewModel.State.Failed -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = { viewModel.load() }) { Text("Tentar de novo") }
        }
        // Scrolls: with the buttons and five entries, a small window would cut the last lines off.
        is HomeViewModel.State.Loaded -> Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Olá, ${s.me.customerName.substringBefore(' ')}", style = MaterialTheme.typography.headlineSmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Saldo disponível", style = MaterialTheme.typography.labelLarge)
                    Text(Format.money(s.me.balanceCents), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Conta ${Format.account(s.me.branch, s.me.accountNumber, s.me.checkDigit)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onTransfer) { Text("Transferir") }
                Button(onClick = onPix) { Text("Pix") }
                OutlinedButton(onClick = onSchedules) { Text("Agendamentos") }
            }
            Text("Últimos lançamentos", style = MaterialTheme.typography.titleMedium)
            if (s.recent.isEmpty()) Text("Nenhum lançamento ainda.")
            s.recent.forEach { EntryRow(it, onClick = onStatement) }
            OutlinedButton(onClick = onStatement) { Text("Ver extrato completo") }
        }
    }
}

@Composable
fun StatementScreen(viewModel: StatementViewModel) {
    val s by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { if (!s.loadedOnce) viewModel.loadMore() }

    // LazyColumn only composes the rows that are visible: long statements stay light.
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        items(s.entries, key = { it.sequence }) { entry -> EntryRow(entry, onClick = { viewModel.open(entry) }) }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    s.loading -> CircularProgressIndicator()
                    s.error != null -> Text(s.error!!, color = MaterialTheme.colorScheme.error)
                    s.hasMore -> OutlinedButton(onClick = { viewModel.loadMore() }) { Text("Carregar mais") }
                    s.loadedOnce && s.entries.isEmpty() -> Text("Nenhum lançamento ainda.")
                }
            }
        }
    }
    s.selected?.let { EntryDetail(it, onClose = viewModel::close) }
}

@Composable
private fun EntryRow(entry: StatementEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Format.shortDate(entry.occurredAt), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
        Column(Modifier.weight(1f)) {
            Text(Format.type(entry.type))
            entry.counterpartyName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(Format.signed(entry), color = if (entry.credit) credit else debit, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider()
}

/** Everything the line already brought - like the console's popup, no new request. */
@Composable
private fun EntryDetail(entry: StatementEntry, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
        title = { Text("${Format.type(entry.type)}  ${Format.signed(entry)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Detail("Data", Format.dateTime(entry.occurredAt))
                Detail("Saldo após", Format.money(entry.balanceAfterCents))
                entry.description?.let { Detail("Descrição", it) }
                entry.counterpartyName?.let { Detail(if (entry.credit) "De" else "Para", it) }
                entry.pix?.let { pix ->
                    pix.counterpartyTaxIdMasked?.let { Detail("CPF/CNPJ", it) }
                    pix.counterpartyInstitution?.let { Detail("Instituição (ISPB)", it) }
                    pix.reasonCode?.let { Detail("Motivo", it) }
                    Detail("EndToEndId", pix.endToEndId)
                }
                Detail("Transação", entry.transactionId)
            }
        },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
