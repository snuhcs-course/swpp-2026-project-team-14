package com.swpp.stylemate.network

import android.content.pm.ApplicationInfo
import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run against the test-signed release APK with -e verifyReleaseNetworkPolicy true. */
@RunWith(AndroidJUnit4::class)
class ReleaseNetworkPolicyTest {
    private val schoolHost = "swpp26t14.147-46-242-169.sslip.io"
    private val policy get() = NetworkSecurityPolicy.getInstance()

    @Before fun requireReleaseFixture() {
        assumeTrue("Requires the explicit release-policy fixture",
            InstrumentationRegistry.getArguments().getString("verifyReleaseNetworkPolicy") == "true")
    }

    @Test fun targetIsNotDebuggable() {
        val flags = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.flags
        assertFalse("The target APK must be release, not debug",
            flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }

    @Test fun exactSchoolHostAllowsCleartext() {
        assertTrue("The school's HTTP-only API must be allowed",
            policy.isCleartextTrafficPermitted(schoolHost))
    }

    @Test fun otherDestinationsDenyCleartext() {
        assertFalse("Cleartext must remain denied by default", policy.isCleartextTrafficPermitted)
        val blockedHosts = listOf(
            "example.com",
            "openrouter.ai",
            "api.open-meteo.com",
            "api.$schoolHost",
            "$schoolHost.attacker.example",
            "not-$schoolHost",
            "147.46.242.169",
            "10.0.2.2",
            "127.0.0.1",
            "localhost",
            "::1",
        )
        blockedHosts.forEach { host ->
            assertFalse("Cleartext must be denied for $host", policy.isCleartextTrafficPermitted(host))
        }
    }
}
