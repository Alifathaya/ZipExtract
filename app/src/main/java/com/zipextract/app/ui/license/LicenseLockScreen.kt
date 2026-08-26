package com.zipextract.app.ui.license

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zipextract.app.R
import com.zipextract.app.license.LicenseGateStatus
import com.zipextract.app.license.LicenseRepository
import com.zipextract.app.license.LicenseUiState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Soft blue lock screen matching the BRI-Link blocked layout. */
private val LockBlue = Color(0xFF1E4F9C)
private val LockBlueSoft = Color(0xFFD6E6F7)
private val LockSky = Color(0xFFE8F2FC)
private val LockMuted = Color(0xFF6B7C90)

internal fun deviceShortCode(deviceId: String): String {
    val hex = deviceId.substringAfterLast('_')
        .filter { it.isLetterOrDigit() }
    return hex.takeLast(8).uppercase().ifBlank { deviceId.takeLast(8).uppercase() }
}

@Composable
fun LicenseLockScreen(
    state: LicenseUiState,
    repository: LicenseRepository,
    onUnlocked: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shortCode = remember(state.deviceId) { deviceShortCode(state.deviceId) }
    val adminWa = stringResource(R.string.license_admin_wa_number)
    val adminWaDisplay = stringResource(R.string.license_admin_wa_display)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(LockSky, Color.White, Color.White),
                ),
            )
            .padding(horizontal = 28.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state.gate) {
            LicenseGateStatus.Loading -> CircularProgressIndicator(color = LockBlue)
            LicenseGateStatus.Disabled, LicenseGateStatus.Active -> {
                CircularProgressIndicator(color = LockBlue)
            }
            LicenseGateStatus.Locked -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = LockBlue,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.license_lock_title),
                        color = LockBlue,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.message
                            ?: stringResource(R.string.license_blocked_with_wa, adminWaDisplay),
                        color = LockMuted,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    state.expiresAtEpochMs?.takeIf { it > 0 }?.let { exp ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.license_expired_on,
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(exp)),
                            ),
                            color = LockMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, LockBlueSoft, RoundedCornerShape(16.dp))
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.license_phone_code_label),
                            color = LockMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = shortCode,
                            color = LockBlue,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.license_device_id, state.deviceId),
                            color = LockMuted.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyText(context, shortCode)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.license_code_copied),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.5.dp, LockBlue),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = LockBlue),
                        ) {
                            Text(
                                text = stringResource(R.string.license_copy_code),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Button(
                            onClick = {
                                openAdminWhatsApp(context, adminWa, shortCode, state.deviceId)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LockBlue,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.license_contact_admin),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    OutlinedTextField(
                        value = key,
                        onValueChange = { value ->
                            if (value.length <= 12) key = value.filter { !it.isWhitespace() }
                        },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.license_key_hint),
                                color = LockMuted.copy(alpha = 0.7f),
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LockBlue,
                            unfocusedBorderColor = Color(0xFFB0BEC5),
                            cursorColor = LockBlue,
                            focusedTextColor = LockBlue,
                            unfocusedTextColor = LockBlue,
                        ),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val canActivate = !state.activating && key.length == 12
                    Button(
                        onClick = {
                            scope.launch {
                                if (repository.activate(key)) onUnlocked()
                            }
                        },
                        enabled = canActivate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LockBlue,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFD7DDE3),
                            disabledContentColor = Color(0xFF7A8694),
                        ),
                    ) {
                        Text(
                            text = if (state.activating) {
                                stringResource(R.string.license_activate) + "…"
                            } else {
                                stringResource(R.string.license_activate)
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    TextButton(
                        onClick = {
                            scope.launch { repository.refresh(forceNetwork = true) }
                        },
                        enabled = !state.activating,
                    ) {
                        Text(
                            text = stringResource(R.string.license_retry_check),
                            color = LockBlue,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("device-code", text))
}

private fun openAdminWhatsApp(
    context: Context,
    waDigits: String,
    shortCode: String,
    deviceId: String,
) {
    val digits = waDigits.filter { it.isDigit() }
    val message = context.getString(R.string.license_wa_prefill, shortCode, deviceId)
    val uri = Uri.parse(
        "https://wa.me/$digits?text=${Uri.encode(message)}",
    )
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.license_wa_open_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
