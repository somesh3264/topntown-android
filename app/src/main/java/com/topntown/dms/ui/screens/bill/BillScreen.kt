package com.topntown.dms.ui.screens.bill

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.topntown.dms.data.datastore.UserSession
import com.topntown.dms.domain.model.Bill
import com.topntown.dms.domain.model.BillItem
import com.topntown.dms.ui.theme.TntPrimary
import java.text.NumberFormat
import java.util.Locale

/**
 * Distributor-facing "Today's Advance Bill" screen.
 *
 * Shows one of three views depending on [BillUiState]:
 *  - Loading  (initial + refresh)
 *  - NoBill   (before 22:00 generation job runs)
 *  - BillReady with items + actions (Download PDF / Share on WhatsApp)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillScreen(
    viewModel: BillViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("Today's Advance Bill") }) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is BillUiState.Loading -> LoadingView()
                is BillUiState.NoBill -> NoBillView(message = s.message)
                is BillUiState.Error -> ErrorView(message = s.message, onRetry = viewModel::loadBill)
                is BillUiState.BillReady -> BillContent(
                    bill = s.bill,
                    items = s.items,
                    distributor = s.distributor,
                    onDownloadPdf = { url ->
                        runCatching {
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(i)
                        }
                    },
                    onShareWhatsApp = { bill, count ->
                        val text = buildShareText(bill, count)
                        val primary = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            setPackage("com.whatsapp")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(primary) }.onFailure {
                            val fallback = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(Intent.createChooser(fallback, "Share bill"))
                        }
                    }
                )
            }
        }
    }
}

/* -------- Sub-views -------- */

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NoBillView(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.AccessTime,
            contentDescription = null,
            tint = TntPrimary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun BillContent(
    bill: Bill,
    items: List<BillItem>,
    distributor: UserSession,
    onDownloadPdf: (String) -> Unit,
    onShareWhatsApp: (Bill, Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { BillInfoCard(bill) }
        item { DistributorInfoCard(distributor) }
        item { ItemsHeader() }
        itemsIndexed(items) { index, item -> BillItemRow(item = item, index = index) }
        item { SummarySection(bill) }
        item {
            ActionButtons(
                pdfUrl = bill.pdfUrl,
                onDownloadPdf = onDownloadPdf,
                onShareWhatsApp = { onShareWhatsApp(bill, items.size) }
            )
        }
    }
}

@Composable
private fun BillInfoCard(bill: Bill) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(bill.billNumber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Bill Date: ${bill.billDate}", style = MaterialTheme.typography.bodyMedium)
            bill.generatedAt?.let {
                Text("Generated: $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DistributorInfoCard(d: UserSession) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(d.fullName, fontWeight = FontWeight.SemiBold)
            Text("Phone: ${d.phone}", style = MaterialTheme.typography.bodyMedium)
            Text("Zone: ${d.zoneId}", style = MaterialTheme.typography.bodyMedium)
            Text("Area: ${d.areaId}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ItemsHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        HeaderCell("Product", 3f)
        HeaderCell("Qty", 1f)
        HeaderCell("Price", 1.2f)
        HeaderCell("Tax", 1f)
        HeaderCell("Total", 1.3f)
    }
}

@Composable
private fun BillItemRow(item: BillItem, index: Int) {
    val bg = if (index % 2 == 0) Color.White else Color(0xFFF5F5F5)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cell(item.productName, 3f)
        Cell(item.allocatedQty.toString(), 1f)
        Cell(formatMoney(item.unitPrice), 1.2f)
        Cell(formatMoney(item.taxAmount), 1f)
        Cell(formatMoney(item.lineTotal), 1.3f, bold = true)
    }
}

@Composable
private fun SummarySection(bill: Bill) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            SummaryRow("Sub-total", formatMoney(bill.subTotal))
            SummaryRow("Total Tax", formatMoney(bill.totalTax))
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Grand Total", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TntPrimary)
                Text(formatMoney(bill.totalAmount), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TntPrimary)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionButtons(
    pdfUrl: String?,
    onDownloadPdf: (String) -> Unit,
    onShareWhatsApp: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            modifier = Modifier.weight(1f),
            enabled = !pdfUrl.isNullOrBlank(),
            onClick = { pdfUrl?.let(onDownloadPdf) },
            colors = ButtonDefaults.buttonColors(containerColor = TntPrimary)
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text("Download PDF")
        }
        OutlinedButton(
            modifier = Modifier.weight(1f),
            onClick = onShareWhatsApp
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text("Share on WhatsApp")
        }
    }
}

/* -------- Helpers -------- */

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, bold: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium
    )
}

private fun formatMoney(value: Double): String {
    val f = NumberFormat.getInstance(Locale("en", "IN"))
    f.minimumFractionDigits = 2
    f.maximumFractionDigits = 2
    return f.format(value)
}

private fun buildShareText(bill: Bill, itemCount: Int): String =
    "Top N Town Bill ${bill.billNumber} | Date: ${bill.billDate} | " +
        "Total: INR ${formatMoney(bill.totalAmount)} | Items: $itemCount"
