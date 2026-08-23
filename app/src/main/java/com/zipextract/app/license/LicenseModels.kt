package com.zipextract.app.license

enum class LicenseGateStatus {
    /** Checking server / loading local cache. */
    Loading,
    /** Allowed to use the app. */
    Active,
    /** Must enter a key (expired / blocked / unknown). */
    Locked,
    /** Licensing disabled (no API URL configured). */
    Disabled,
}

data class LicenseUiState(
    val gate: LicenseGateStatus = LicenseGateStatus.Loading,
    val expiresAtEpochMs: Long? = null,
    val deviceId: String = "",
    val message: String? = null,
    val activating: Boolean = false,
)
