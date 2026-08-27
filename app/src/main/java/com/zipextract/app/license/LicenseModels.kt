package com.zipextract.app.license

enum class LicenseGateStatus {
    /**
     * Transient / unused on cold start — UI treats this like [Active]
     * so the file browser opens immediately while a background check runs.
     */
    Loading,
    /** Allowed to use the app. */
    Active,
    /** Must enter a key (expired / blocked / unknown). */
    Locked,
    /** Licensing disabled (no API URL configured). */
    Disabled,
}

data class AppUpdateInfo(
    val version: String,
    val message: String,
    val url: String,
    val createdAt: String = "",
) {
    fun dismissKey(): String = "$version|$createdAt"
}

data class LicenseUiState(
    val gate: LicenseGateStatus = LicenseGateStatus.Loading,
    val expiresAtEpochMs: Long? = null,
    val deviceId: String = "",
    val message: String? = null,
    val activating: Boolean = false,
    val unlimited: Boolean = false,
    val pendingUpdate: AppUpdateInfo? = null,
)
