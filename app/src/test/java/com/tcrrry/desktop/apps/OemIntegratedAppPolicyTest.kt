package com.tcrrry.desktop.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemIntegratedAppPolicyTest {
    @Test
    fun `excludes verified vehicle WeChat only on the validated firmware family`() {
        assertTrue(OemIntegratedAppPolicy.requiresEvidence(targetDevice, WECHAT_PACKAGE))
        assertTrue(
            OemIntegratedAppPolicy.shouldExclude(
                targetDevice,
                evidence(
                    signingCertificateSha256Digests = setOf(TENCENT_CERTIFICATE_SHA256),
                    providerClassNames = setOf(WECAR_ACCOUNT_PROVIDER),
                ),
            ),
        )

        assertFalse(
            OemIntegratedAppPolicy.shouldExclude(
                targetDevice.copy(model = "another_model"),
                evidence(
                    signingCertificateSha256Digests = setOf(TENCENT_CERTIFICATE_SHA256),
                    providerClassNames = setOf(WECAR_ACCOUNT_PROVIDER),
                ),
            ),
        )
        assertFalse(
            OemIntegratedAppPolicy.shouldExclude(
                targetDevice.copy(fingerprint = "OTHER/device/device:9/build/1:user/release-keys"),
                evidence(
                    signingCertificateSha256Digests = setOf(TENCENT_CERTIFICATE_SHA256),
                    providerClassNames = setOf(WECAR_ACCOUNT_PROVIDER),
                ),
            ),
        )
    }

    @Test
    fun `keeps phone WeChat resigned packages and other Tencent apps visible`() {
        assertFalse(
            OemIntegratedAppPolicy.shouldExclude(
                targetDevice,
                evidence(
                    signingCertificateSha256Digests = setOf(TENCENT_CERTIFICATE_SHA256),
                    providerClassNames = emptySet(),
                ),
            ),
        )
        assertFalse(
            OemIntegratedAppPolicy.shouldExclude(
                targetDevice,
                evidence(
                    signingCertificateSha256Digests = setOf("different-certificate"),
                    providerClassNames = setOf(WECAR_ACCOUNT_PROVIDER),
                ),
            ),
        )
        assertFalse(
            OemIntegratedAppPolicy.shouldExclude(
                targetDevice,
                evidence(
                    packageName = "com.tencent.other",
                    signingCertificateSha256Digests = setOf(TENCENT_CERTIFICATE_SHA256),
                    providerClassNames = setOf(WECAR_ACCOUNT_PROVIDER),
                ),
            ),
        )
    }

    private fun evidence(
        packageName: String = WECHAT_PACKAGE,
        signingCertificateSha256Digests: Set<String>,
        providerClassNames: Set<String>,
    ) = OemAppEvidence(
        packageName = packageName,
        signingCertificateSha256Digests = signingCertificateSha256Digests,
        providerClassNames = providerClassNames,
    )

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val TENCENT_CERTIFICATE_SHA256 =
            "0fe4ff85c215918396dadc7cd8ce6963339af33d37751a56e54c7206b63a3c7c"
        const val WECAR_ACCOUNT_PROVIDER = "com.tencent.wecarbase.common.AccountProvider"
        val targetDevice = OemDeviceProfile(
            model = "S56_HQX",
            device = "msmnile_gvmq",
            sdkInt = 28,
            fingerprint = "MENGBO/msmnile_gvmq/msmnile_gvmq:9/PQ3B.190801.002/1:user/test-keys",
        )
    }
}
