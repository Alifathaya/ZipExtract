package com.zipextract.app.license

import android.app.Application
import android.content.Context
import com.zipextract.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class LicenseRepository private constructor(
    private val app: Application,
    private val api: LicenseApi = LicenseApi(),
    private val store: LicenseStore = LicenseStore(app),
) {
    private val _state = MutableStateFlow(buildInitialState())
    val state: StateFlow<LicenseUiState> = _state.asStateFlow()

    private var pushClient: LicensePushClient? = null

    fun deviceId(): String = DeviceIdProvider.get(app)

    fun startPushChannel() {
        if (!api.isConfigured()) return
        if (pushClient != null) return
        val client = LicensePushClient(
            deviceId = deviceId(),
            onLicense = { res -> applyServerResponse(res) },
        )
        pushClient = client
        client.start()
    }

    fun stopPushChannel() {
        pushClient?.stop()
        pushClient = null
    }

    /**
     * Fast path for UI: use local cache only (no network, never Loading).
     * Call [silentCheck] afterward to verify with the server in the background.
     */
    fun applyLocalCache() {
        if (!api.isConfigured()) {
            _state.value = LicenseUiState(
                gate = LicenseGateStatus.Disabled,
                deviceId = deviceId(),
            )
            return
        }
        applyOfflineGate(allowProvisional = true)
    }

    /**
     * @deprecated Prefer [applyLocalCache] + [silentCheck] so the UI never waits on network.
     * Kept for lock-screen "retry" — still avoids a full-screen Loading flash.
     */
    suspend fun refresh(forceNetwork: Boolean = true) {
        if (!api.isConfigured()) {
            _state.value = LicenseUiState(
                gate = LicenseGateStatus.Disabled,
                deviceId = deviceId(),
            )
            return
        }
        // Never set Loading — open the app immediately from local cache.
        applyOfflineGate(allowProvisional = true)
        if (forceNetwork) {
            withContext(Dispatchers.IO) {
                evaluate(forceNetwork = true)
            }
        }
    }

    /**
     * Background / midnight check — updates store; does not need UI loading state.
     */
    suspend fun silentCheck(): Boolean = withContext(Dispatchers.IO) {
        if (!api.isConfigured()) return@withContext true
        try {
            evaluate(forceNetwork = true)
            _state.value.gate == LicenseGateStatus.Active ||
                _state.value.gate == LicenseGateStatus.Disabled
        } catch (_: Exception) {
            applyOfflineGate(allowProvisional = true)
            _state.value.gate == LicenseGateStatus.Active
        }
    }

    suspend fun activate(key: String): Boolean {
        if (!api.isConfigured()) return true
        val trimmed = key.trim()
        if (trimmed.length != 12) {
            _state.value = _state.value.copy(
                message = app.getString(com.zipextract.app.R.string.license_key_invalid_length),
                activating = false,
            )
            return false
        }
        _state.value = _state.value.copy(activating = true, message = null)
        return withContext(Dispatchers.IO) {
            try {
                val res = api.activate(deviceId(), trimmed)
                if (res.error != null) {
                    _state.value = _state.value.copy(
                        activating = false,
                        gate = LicenseGateStatus.Locked,
                        message = mapError(res.error),
                    )
                    return@withContext false
                }
                applyServerResponse(res)
                _state.value = _state.value.copy(
                    activating = false,
                    message = app.getString(com.zipextract.app.R.string.license_activated_ok),
                )
                true
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    activating = false,
                    gate = LicenseGateStatus.Locked,
                    message = app.getString(com.zipextract.app.R.string.license_network_error),
                )
                false
            }
        }
    }

    private fun buildInitialState(): LicenseUiState {
        if (!api.isConfigured()) {
            return LicenseUiState(
                gate = LicenseGateStatus.Disabled,
                deviceId = DeviceIdProvider.get(app),
            )
        }
        // Synchronous local decision so first frame is never a spinner.
        return computeOfflineState(allowProvisional = true)
    }

    private fun evaluate(forceNetwork: Boolean) {
        val id = deviceId()

        // Always prefer server when online. Local blocked/expired is only a cache —
        // admin unblock/extend must be able to unlock the phone without clearing app data.
        if (forceNetwork || !store.registered || store.expiresAtEpochMs <= 0L || store.blocked) {
            try {
                val res = if (!store.registered || store.expiresAtEpochMs <= 0L) {
                    api.register(id, BuildConfig.VERSION_NAME)
                } else {
                    api.check(id)
                }
                if (res.error == "unknown_device") {
                    val reg = api.register(id, BuildConfig.VERSION_NAME)
                    applyServerResponse(reg)
                    return
                }
                if (res.error == "device_blocked") {
                    store.blocked = true
                    store.registered = true
                    _state.value = LicenseUiState(
                        gate = LicenseGateStatus.Locked,
                        expiresAtEpochMs = store.expiresAtEpochMs.takeIf { it > 0 },
                        deviceId = id,
                        message = app.getString(com.zipextract.app.R.string.license_blocked),
                    )
                    return
                }
                if (res.error != null && res.expiresAtEpochMs <= 0L) {
                    applyOfflineGate(
                        allowProvisional = true,
                        fallbackMessage = mapError(res.error),
                    )
                    return
                }
                applyServerResponse(res)
                return
            } catch (_: Exception) {
                applyOfflineGate(allowProvisional = true)
                return
            }
        }

        applyOfflineGate(allowProvisional = true)
        if (forceNetwork) {
            try {
                val res = api.check(id)
                applyServerResponse(res)
            } catch (_: Exception) {
                // keep offline gate result
            }
        }
    }

    private fun applyServerResponse(res: LicenseServerResponse) {
        val id = deviceId()
        val unlimited = res.unlimited || LicenseApi.isUnlimitedEpoch(res.expiresAtEpochMs)
        when (res.status) {
            "blocked" -> {
                store.blocked = true
                store.registered = true
                store.unlimited = unlimited
                if (res.expiresAtEpochMs > 0) store.expiresAtEpochMs = res.expiresAtEpochMs
                store.lastSuccessfulCheckAtEpochMs = System.currentTimeMillis()
                _state.value = LicenseUiState(
                    gate = LicenseGateStatus.Locked,
                    expiresAtEpochMs = store.expiresAtEpochMs.takeIf { it > 0 },
                    deviceId = id,
                    message = app.getString(com.zipextract.app.R.string.license_blocked),
                    unlimited = unlimited,
                )
            }
            "expired" -> {
                store.blocked = false
                store.registered = true
                store.unlimited = false
                store.expiresAtEpochMs = res.expiresAtEpochMs
                store.lastSuccessfulCheckAtEpochMs = System.currentTimeMillis()
                _state.value = LicenseUiState(
                    gate = LicenseGateStatus.Locked,
                    expiresAtEpochMs = res.expiresAtEpochMs.takeIf { it > 0 },
                    deviceId = id,
                    message = app.getString(com.zipextract.app.R.string.license_expired),
                    unlimited = false,
                )
            }
            else -> {
                store.blocked = false
                store.registered = true
                store.unlimited = unlimited
                if (res.expiresAtEpochMs > 0) store.expiresAtEpochMs = res.expiresAtEpochMs
                store.lastSuccessfulCheckAtEpochMs = System.currentTimeMillis()
                val now = System.currentTimeMillis()
                val active = unlimited || store.expiresAtEpochMs > now
                _state.value = LicenseUiState(
                    gate = if (active) LicenseGateStatus.Active else LicenseGateStatus.Locked,
                    expiresAtEpochMs = store.expiresAtEpochMs.takeIf { it > 0 },
                    deviceId = id,
                    message = if (active) {
                        null
                    } else {
                        app.getString(com.zipextract.app.R.string.license_expired)
                    },
                    unlimited = unlimited,
                )
            }
        }
    }

    private fun applyOfflineGate(
        allowProvisional: Boolean = false,
        fallbackMessage: String? = null,
    ) {
        _state.value = computeOfflineState(
            allowProvisional = allowProvisional,
            fallbackMessage = fallbackMessage,
        )
    }

    private fun computeOfflineState(
        allowProvisional: Boolean,
        fallbackMessage: String? = null,
    ): LicenseUiState {
        val id = deviceId()
        val now = System.currentTimeMillis()
        if (store.blocked) {
            return LicenseUiState(
                gate = LicenseGateStatus.Locked,
                expiresAtEpochMs = store.expiresAtEpochMs.takeIf { it > 0 },
                deviceId = id,
                message = app.getString(com.zipextract.app.R.string.license_blocked),
                unlimited = store.unlimited,
            )
        }
        if (store.unlimited || LicenseApi.isUnlimitedEpoch(store.expiresAtEpochMs)) {
            return LicenseUiState(
                gate = LicenseGateStatus.Active,
                expiresAtEpochMs = store.expiresAtEpochMs.takeIf { it > 0 },
                deviceId = id,
                message = fallbackMessage,
                unlimited = true,
            )
        }
        val expires = store.expiresAtEpochMs
        if (expires <= 0L) {
            // First launch / not registered yet: open immediately; server verify runs in background.
            if (allowProvisional) {
                return LicenseUiState(
                    gate = LicenseGateStatus.Active,
                    deviceId = id,
                    message = fallbackMessage,
                )
            }
            return LicenseUiState(
                gate = LicenseGateStatus.Locked,
                deviceId = id,
                message = fallbackMessage
                    ?: app.getString(com.zipextract.app.R.string.license_need_online),
            )
        }
        if (expires <= now) {
            return LicenseUiState(
                gate = LicenseGateStatus.Locked,
                expiresAtEpochMs = expires,
                deviceId = id,
                message = app.getString(com.zipextract.app.R.string.license_expired),
            )
        }
        val last = store.lastSuccessfulCheckAtEpochMs
        if (last > 0L && now - last > LicenseStore.GRACE_MS) {
            return LicenseUiState(
                gate = LicenseGateStatus.Locked,
                expiresAtEpochMs = expires,
                deviceId = id,
                message = app.getString(com.zipextract.app.R.string.license_need_online),
            )
        }
        return LicenseUiState(
            gate = LicenseGateStatus.Active,
            expiresAtEpochMs = expires,
            deviceId = id,
            message = fallbackMessage,
        )
    }

    private fun mapError(code: String): String {
        return when (code) {
            "invalid_key" -> app.getString(com.zipextract.app.R.string.license_key_invalid)
            "key_already_used" -> app.getString(com.zipextract.app.R.string.license_key_used)
            "device_blocked" -> app.getString(com.zipextract.app.R.string.license_blocked)
            else -> app.getString(com.zipextract.app.R.string.license_activate_failed, code)
        }
    }

    companion object {
        @Volatile
        private var instance: LicenseRepository? = null

        fun get(context: Context): LicenseRepository {
            val app = context.applicationContext as Application
            return instance ?: synchronized(this) {
                instance ?: LicenseRepository(app).also { instance = it }
            }
        }
    }
}
