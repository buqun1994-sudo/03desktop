package com.ninepointnine.desktop.apps

internal data class OemDeviceProfile(
    val model: String,
    val device: String,
    val sdkInt: Int,
    val fingerprint: String,
)

internal data class OemAppEvidence(
    val packageName: String,
    val signingCertificateSha256Digests: Set<String>,
    val providerClassNames: Set<String>,
)

internal object OemIntegratedAppPolicy {
    private val rules = listOf(
        Rule(
            model = "S56_HQX",
            device = "msmnile_gvmq",
            sdkInt = 28,
            fingerprintPrefix = "MENGBO/msmnile_gvmq/msmnile_gvmq:9/",
            packageName = "com.tencent.mm",
            signingCertificateSha256 =
                "0fe4ff85c215918396dadc7cd8ce6963339af33d37751a56e54c7206b63a3c7c",
            requiredProviderClassName = "com.tencent.wecarbase.common.AccountProvider",
        ),
    )

    fun requiresEvidence(deviceProfile: OemDeviceProfile, packageName: String): Boolean =
        rules.any { rule -> rule.matchesDevice(deviceProfile) && rule.packageName == packageName }

    fun shouldExclude(deviceProfile: OemDeviceProfile, evidence: OemAppEvidence): Boolean =
        rules.any { rule ->
            rule.matchesDevice(deviceProfile) &&
                rule.packageName == evidence.packageName &&
                rule.signingCertificateSha256 in evidence.signingCertificateSha256Digests &&
                rule.requiredProviderClassName in evidence.providerClassNames
        }

    private data class Rule(
        val model: String,
        val device: String,
        val sdkInt: Int,
        val fingerprintPrefix: String,
        val packageName: String,
        val signingCertificateSha256: String,
        val requiredProviderClassName: String,
    ) {
        fun matchesDevice(deviceProfile: OemDeviceProfile): Boolean =
            deviceProfile.model == model &&
                deviceProfile.device == device &&
                deviceProfile.sdkInt == sdkInt &&
                deviceProfile.fingerprint.startsWith(fingerprintPrefix)
    }
}
