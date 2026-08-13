package com.zipextract.app.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zipextract.app.ui.ExtractZipState

/**
 * Compact extract confirmation shown when the user taps a ZIP.
 * Extract button + optional delete-ZIP checkbox; results go to Download/FileNest.
 */
@Composable
fun ExtractZipDialog(
    state: ExtractZipState,
    onClose: () -> Unit,
    onDeleteOriginalChange: (Boolean) -> Unit,
    onExtract: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                text = "Extract ZIP",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = state.zipFile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Hasil extract muncul di atas list halaman Download.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.deleteOriginal,
                        onCheckedChange = onDeleteOriginalChange,
                    )
                    Text(
                        text = "Hapus file ZIP setelah extract",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onExtract) {
                Text("Extract")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) {
                Text("Batal")
            }
        },
    )
}
