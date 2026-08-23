package com.zipextract.app.ui.license

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zipextract.app.R
import com.zipextract.app.license.LicenseGateStatus
import com.zipextract.app.license.LicenseRepository
import com.zipextract.app.license.LicenseUiState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun LicenseLockScreen(
    state: LicenseUiState,
    repository: LicenseRepository,
    onUnlocked: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Polling for admin Unblock lives in MainActivity (shared foreground loop).

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state.gate) {
            LicenseGateStatus.Loading -> CircularProgressIndicator()
            LicenseGateStatus.Disabled, LicenseGateStatus.Active -> {
                // Host should not show this screen for these states.
                CircularProgressIndicator()
            }
            LicenseGateStatus.Locked -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = stringResource(R.string.license_lock_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = state.message ?: stringResource(R.string.license_expired),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    state.expiresAtEpochMs?.takeIf { it > 0 }?.let { exp ->
                        Text(
                            text = stringResource(
                                R.string.license_expired_on,
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(exp)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = key,
                        onValueChange = { value ->
                            if (value.length <= 12) key = value
                        },
                        label = { Text(stringResource(R.string.license_key_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                if (repository.activate(key)) onUnlocked()
                            }
                        },
                        enabled = !state.activating && key.length == 12,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.activating) {
                                stringResource(R.string.license_activate) + "…"
                            } else {
                                stringResource(R.string.license_activate)
                            },
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch { repository.refresh(forceNetwork = true) }
                        },
                        enabled = !state.activating,
                    ) {
                        Text(stringResource(R.string.license_retry_check))
                    }
                    Text(
                        text = stringResource(R.string.license_device_id, state.deviceId),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
