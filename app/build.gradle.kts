import java.io.File
import java.util.Properties

fun Properties.requiredSigningValue(name: String): String =
    getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Signing property '$name' is required")

fun Properties.requiredReleaseValue(name: String): String =
    getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Release version property '$name' is required")

val releaseVersionPropertiesFile = rootProject.file("release-version.properties")
val releaseVersionProperties = Properties().apply {
    require(releaseVersionPropertiesFile.isFile) {
        "Release version properties file does not exist: $releaseVersionPropertiesFile"
    }
    releaseVersionPropertiesFile.inputStream().use(::load)
}
val releaseVersionName = releaseVersionProperties.requiredReleaseValue("releaseVersionName")
val releaseVersionCode = releaseVersionProperties.requiredReleaseValue("releaseVersionCode").toIntOrNull()
    ?: error("Release version property 'releaseVersionCode' must be a positive integer")
require(releaseVersionCode > 0) {
    "Release version property 'releaseVersionCode' must be a positive integer"
}
require(Regex("\\d+\\.\\d+\\.\\d+-icar03").matches(releaseVersionName)) {
    "Release version name must match <major>.<minor>.<patch>-icar03"
}

val desktopSigningEnvironment = providers.gradleProperty("desktopSigningEnvironment")
    .orElse("debug")
    .get()
    .trim()
    .lowercase()
require(desktopSigningEnvironment in setOf("debug", "staging")) {
    "desktopSigningEnvironment must be debug or staging"
}

val stagingSigningPropertiesFile = providers.gradleProperty("desktopStagingSigningPropertiesFile")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(rootProject::file)
val stagingSigningProperties = Properties()
val stagingSigningStoreFile = if (desktopSigningEnvironment == "staging") {
    val propertiesFile = requireNotNull(stagingSigningPropertiesFile) {
        "Staging APK signing properties file is required"
    }
    require(propertiesFile.isFile) {
        "Staging APK signing properties file does not exist"
    }
    propertiesFile.inputStream().use(stagingSigningProperties::load)
    val configuredStoreFile = stagingSigningProperties.requiredSigningValue("storeFile")
    val candidate = File(configuredStoreFile)
    val resolved = if (candidate.isAbsolute) candidate else propertiesFile.parentFile.resolve(configuredStoreFile)
    require(resolved.isFile) { "Staging APK keystore does not exist" }
    resolved
} else {
    null
}

val productionSigningPropertiesFile = providers.gradleProperty("desktopProductionSigningPropertiesFile")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(rootProject::file)
val productionSigningProperties = Properties()
val productionSigningStoreFile = productionSigningPropertiesFile?.let { propertiesFile ->
    require(propertiesFile.isFile) {
        "Production APK signing properties file does not exist"
    }
    propertiesFile.inputStream().use(productionSigningProperties::load)
    val configuredStoreFile = productionSigningProperties.requiredSigningValue("storeFile")
    val candidate = File(configuredStoreFile)
    val resolved = if (candidate.isAbsolute) candidate else propertiesFile.parentFile.resolve(configuredStoreFile)
    require(resolved.isFile) { "Production APK keystore does not exist" }
    resolved
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ninepointnine.desktop"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ninepointnine.desktop"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (productionSigningStoreFile != null) {
                storeFile = productionSigningStoreFile
                storePassword = productionSigningProperties.requiredSigningValue("storePassword")
                keyAlias = productionSigningProperties.requiredSigningValue("keyAlias")
                keyPassword = productionSigningProperties.requiredSigningValue("keyPassword")
            }
        }
        if (stagingSigningStoreFile != null) {
            create("staging") {
                storeFile = stagingSigningStoreFile
                storePassword = stagingSigningProperties.requiredSigningValue("storePassword")
                keyAlias = stagingSigningProperties.requiredSigningValue("keyAlias")
                keyPassword = stagingSigningProperties.requiredSigningValue("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("staging")?.let { signingConfig = it }
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.versionName.set(releaseVersionName)
            output.versionCode.set(releaseVersionCode)
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild" || name == "validateSigningRelease") {
        doFirst {
            require(productionSigningStoreFile != null) {
                "Production APK signing properties file is required. " +
                    "Pass -PdesktopProductionSigningPropertiesFile=<path-to-signing.properties>."
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
