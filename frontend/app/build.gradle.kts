// AI-generated with Claude Code (Dongkun Moon) and Codex (Hyeon U Jeong), 2026-09-24, reviewed by Dongkun Moon and Hyeon U Jeong
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Debug keeps local development; release uses a separate school API endpoint.
// Set these properties in local.properties or override them with -P.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val schoolApiHost = "swpp26t14.147-46-242-169.sslip.io"
val debugApiBaseUrl = providers.gradleProperty("stylemate.apiBaseUrl")
    .orElse(localProperties.getProperty("stylemate.apiBaseUrl", "http://10.0.2.2:8000")).get()
val releaseApiBaseUrl = providers.gradleProperty("stylemate.releaseApiBaseUrl")
    .orElse(localProperties.getProperty("stylemate.releaseApiBaseUrl", "http://$schoolApiHost"))
    .get().trimEnd('/')
val releaseApiUri = runCatching { URI(releaseApiBaseUrl) }.getOrNull()
require(releaseApiUri != null && !releaseApiUri.host.isNullOrBlank()
    && releaseApiUri.rawUserInfo == null && releaseApiUri.rawQuery == null && releaseApiUri.rawFragment == null
    && (releaseApiUri.port == -1 || releaseApiUri.port in 1..65535)
    && (releaseApiUri.scheme.equals("https", ignoreCase = true)
        || (releaseApiUri.scheme.equals("http", ignoreCase = true)
            && releaseApiUri.host.equals(schoolApiHost, ignoreCase = true)
            && (releaseApiUri.port == -1 || releaseApiUri.port == 80)))) {
    "stylemate.releaseApiBaseUrl must use HTTPS or HTTP on $schoolApiHost port 80, without credentials, query or fragment."
}

android {
    namespace = "com.swpp.stylemate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.swpp.stylemate"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 3
        versionName = "0.2.1"
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
            // JaCoCo line/branch coverage: createDebugUnitTestCoverageReport (JVM unit tests).
            // Device-test coverage instruments the debug APK itself, so it is opt-in:
            // -PandroidTestCoverage createDebugAndroidTestCoverageReport, or createAggregatedCoverageReport
            // for unit + device tests together; see the wiki Testing Documentation
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = project.hasProperty("androidTestCoverage")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)
    implementation("com.google.ar:core:1.48.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.okhttp)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test) // viewModelScope(Main) 대체용
    testImplementation(libs.org.json) // real org.json for JVM tests (Android's copy is a stub there)
}
