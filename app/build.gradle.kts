import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

dependencyLocking {
    lockAllConfigurations()
}

fun signingProperty(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull

fun buildConfigString(value: String): String =
    "\"${value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")}\""

fun environmentOrFallback(name: String, fallback: String): String =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() } ?: fallback

fun commandOutput(vararg command: String): String? = runCatching {
    val process = ProcessBuilder(*command)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    output.takeIf { process.waitFor() == 0 && it.isNotBlank() }
}.getOrNull()

fun sha256Digest(vararg paths: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    paths.sorted().forEach { path ->
        val source = rootProject.file(path)
        digest.update(path.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        if (source.isFile) digest.update(source.readBytes()) else digest.update("MISSING".toByteArray())
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun signingFingerprint(path: String?, password: String?, alias: String?): String {
    if (path == null || password == null || alias == null) return "unsigned"
    val keystoreFile = rootProject.file(path)
    if (!keystoreFile.isFile) return "unsigned"
    return runCatching {
        val type = if (keystoreFile.extension.equals("p12", ignoreCase = true) ||
            keystoreFile.extension.equals("pfx", ignoreCase = true)
        ) "PKCS12" else KeyStore.getDefaultType()
        val keystore = KeyStore.getInstance(type)
        FileInputStream(keystoreFile).use { input -> keystore.load(input, password.toCharArray()) }
        val certificate = keystore.getCertificate(alias) ?: return@runCatching "unavailable"
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        "sha256:" + digest.joinToString("") { byte -> "%02x".format(byte) }
    }.getOrDefault("unavailable")
}

val appVersionCode = 6
val appVersionName = "1.1.1"
require(Regex("\\d+\\.\\d+\\.\\d+").matches(appVersionName)) {
    "Hermes versionName must be semantic major.minor.patch."
}
tasks.cyclonedxDirectBom {
    includeConfigs = listOf("releaseRuntimeClasspath")
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    componentName.set("hermes-android")
    componentVersion.set(appVersionName)
}
val auditedHermesCommit = "b9aa9289a8083f2e9d248ad6837b2938f5ee92d7"
val hermesAgentVersion = "0.20.0"
val hermesDesktopVersion = "0.17.0"
val androidCommit = environmentOrFallback(
    "GITHUB_SHA",
    commandOutput("git", "rev-parse", "HEAD") ?: "unknown",
)
val buildIdentity = listOf(
    environmentOrFallback("GITHUB_REPOSITORY", "local"),
    environmentOrFallback("GITHUB_RUN_ID", "local"),
    environmentOrFallback("GITHUB_RUN_ATTEMPT", "1"),
    environmentOrFallback("GITHUB_WORKFLOW", "working-tree"),
    environmentOrFallback("GITHUB_REF_NAME", "working-tree"),
).joinToString("/")
val dependencyLockPath = "app/gradle.lockfile"
val toolchainDigest = "sha256:" + sha256Digest(
    "gradle/libs.versions.toml",
    "gradle/wrapper/gradle-wrapper.properties",
    dependencyLockPath,
    "settings-gradle.lockfile",
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
)
val debugKeystorePath = signingProperty("HERMES_DEBUG_KEYSTORE_PATH")
val debugKeystorePassword = signingProperty("HERMES_DEBUG_KEYSTORE_PASSWORD")
val debugKeyAlias = signingProperty("HERMES_DEBUG_KEY_ALIAS")
val debugKeyPassword = signingProperty("HERMES_DEBUG_KEY_PASSWORD")
val releaseKeystorePath = signingProperty("HERMES_RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword = signingProperty("HERMES_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperty("HERMES_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingProperty("HERMES_RELEASE_KEY_PASSWORD")

val configuredDebugFingerprint = signingFingerprint(debugKeystorePath, debugKeystorePassword, debugKeyAlias)
val configuredReleaseFingerprint = signingFingerprint(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias)

val provenanceChannel = providers.gradleProperty("hermes.provenance.channel").orElse("debug").get()
require(provenanceChannel == "debug" || provenanceChannel == "release") {
    "hermes.provenance.channel must be debug or release."
}
require(provenanceChannel != "release" || rootProject.file(dependencyLockPath).isFile) {
    "Release provenance requires the committed app/gradle.lockfile."
}
val provenanceOutput = layout.buildDirectory.file("generated/provenance/hermes-android-$provenanceChannel.properties")
val provenancePackageName = if (provenanceChannel == "release") {
    "com.nousresearch.hermes"
} else {
    "com.nousresearch.hermes.debug"
}
val provenanceVersionName = if (provenanceChannel == "release") appVersionName else "$appVersionName-debug"
val provenanceSigningFingerprint = if (provenanceChannel == "release") {
    configuredReleaseFingerprint
} else {
    configuredDebugFingerprint
}
val provenanceContent = listOf(
    "android.version=$provenanceVersionName",
    "android.version_code=$appVersionCode",
    "android.channel=$provenanceChannel",
    "android.commit=$androidCommit",
    "hermes.audited_commit=$auditedHermesCommit",
    "hermes.agent_version=$hermesAgentVersion",
    "hermes.agent_version_range==${hermesAgentVersion}",
    "hermes.desktop_version=$hermesDesktopVersion",
    "hermes.desktop_version_range==${hermesDesktopVersion}",
    "toolchain.digest=$toolchainDigest",
    "build.identity=$buildIdentity",
    "build.package=$provenancePackageName",
    "build.signing_fingerprint=$provenanceSigningFingerprint",
    "build.author=luinbytes",
).joinToString("\n", postfix = "\n")

tasks.register("writeBuildProvenance") {
    inputs.property("content", provenanceContent)
    outputs.file(provenanceOutput)
    doLast {
        val output = provenanceOutput.get().asFile
        output.parentFile.mkdirs()
        output.writeText(provenanceContent)
    }
}

val hasDebugSigning = debugKeystorePath != null &&
    debugKeystorePassword != null &&
    debugKeyAlias != null &&
    debugKeyPassword != null &&
    file(debugKeystorePath).isFile
val hasReleaseSigning = releaseKeystorePath != null &&
    releaseKeystorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    file(releaseKeystorePath).isFile

check(signingProperty("HERMES_REQUIRE_DEBUG_SIGNING") != "true" || hasDebugSigning) {
    "Stable debug signing was required, but the Hermes debug keystore configuration is incomplete."
}
check(signingProperty("HERMES_REQUIRE_RELEASE_SIGNING") != "true" || hasReleaseSigning) {
    "Release signing was required, but the Hermes release keystore configuration is incomplete."
}

android {
    namespace = "com.nousresearch.hermes"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nousresearch.hermes"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "HERMES_ANDROID_COMMIT", buildConfigString(androidCommit))
        buildConfigField("String", "HERMES_AUDIT_COMMIT", buildConfigString(auditedHermesCommit))
        buildConfigField("String", "HERMES_AGENT_VERSION", buildConfigString(hermesAgentVersion))
        buildConfigField("String", "HERMES_AGENT_VERSION_RANGE", buildConfigString("=$hermesAgentVersion"))
        buildConfigField("String", "HERMES_DESKTOP_VERSION", buildConfigString(hermesDesktopVersion))
        buildConfigField("String", "HERMES_DESKTOP_VERSION_RANGE", buildConfigString("=$hermesDesktopVersion"))
        buildConfigField("String", "HERMES_TOOLCHAIN_DIGEST", buildConfigString(toolchainDigest))
        buildConfigField("String", "HERMES_BUILD_IDENTITY", buildConfigString(buildIdentity))
        buildConfigField("String", "HERMES_BUILD_AUTHOR", buildConfigString("luinbytes"))
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (hasDebugSigning) {
            getByName("debug") {
                storeFile = file(debugKeystorePath!!)
                storePassword = debugKeystorePassword
                keyAlias = debugKeyAlias
                keyPassword = debugKeyPassword
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "HERMES_BUILD_CHANNEL", buildConfigString("debug"))
            buildConfigField("String", "HERMES_SIGNING_FINGERPRINT", buildConfigString(configuredDebugFingerprint))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "HERMES_BUILD_CHANNEL", buildConfigString("release"))
            buildConfigField("String", "HERMES_SIGNING_FINGERPRINT", buildConfigString(configuredReleaseFingerprint))
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"

    packaging.resources.excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "META-INF/DEPENDENCIES",
    )

    testOptions {
        unitTests.isIncludeAndroidResources = true
        managedDevices {
            allDevices {
                create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel2Api28") {
                    device = "Pixel 2"
                    apiLevel = 28
                    systemImageSource = "google"
                }
                create<com.android.build.api.dsl.ManagedVirtualDevice>("pixelTabletApi36") {
                    device = "Pixel Tablet"
                    apiLevel = 36
                    systemImageSource = "google"
                }
            }
        }
    }
}

androidComponents {
    onVariants(selector().withName("benchmarkRelease")) { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("src/benchmarkRelease/java")
        variant.sources.manifests.addStaticManifestFile("src/benchmarkRelease/AndroidManifest.xml")
    }
    onVariants(selector().withName("nonMinifiedRelease")) { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("src/nonMinifiedRelease/java")
        variant.sources.manifests.addStaticManifestFile("src/nonMinifiedRelease/AndroidManifest.xml")
    }
}

dependencies {
    baselineProfile(project(":benchmark"))

    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markdown.code)
    implementation(libs.markdown.m3)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
