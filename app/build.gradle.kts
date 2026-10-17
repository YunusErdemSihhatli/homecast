import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spotless)
}

// Release signing: each value comes from its environment variable (CI) or, failing that, from the
// git-ignored keystore.properties at the repository root. storeFile may be absolute or relative to the
// repository root. Without a complete configuration release builds are signed with the debug key and a
// warning, unless HOMECAST_REQUIRE_RELEASE_SIGNING=true (set by the release workflow) makes them fail.
val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }

/** Environment variable to keystore.properties key, for every value release signing needs. */
val releaseSigningSources =
    linkedMapOf(
        "HOMECAST_KEYSTORE_FILE" to "storeFile",
        "HOMECAST_KEYSTORE_PASSWORD" to "storePassword",
        "HOMECAST_KEY_ALIAS" to "keyAlias",
        "HOMECAST_KEY_PASSWORD" to "keyPassword",
    )
val releaseSigning: Map<String, String?> =
    releaseSigningSources.mapValues { (env, property) ->
        System.getenv(env)?.takeIf { it.isNotBlank() }
            ?: keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }
    }
val missingReleaseSigning = releaseSigning.filterValues { it == null }.keys
val hasReleaseSigning = missingReleaseSigning.isEmpty()
val requireReleaseSigning = System.getenv("HOMECAST_REQUIRE_RELEASE_SIGNING") == "true"

/** Names what is missing (never the values) for the warning or failure message. */
fun releaseSigningProblem(): String =
    if (missingReleaseSigning.size == releaseSigningSources.size) {
        "no release keystore configured (HOMECAST_KEYSTORE_* env vars or keystore.properties)"
    } else {
        "release signing is incomplete; missing " +
            missingReleaseSigning.joinToString { env ->
                val property = releaseSigningSources.getValue(env)
                "$env (or $property in keystore.properties)"
            }
    }

gradle.taskGraph.whenReady {
    if (!hasReleaseSigning && allTasks.any { it.path == ":app:packageRelease" }) {
        if (requireReleaseSigning) {
            throw GradleException("HomeCast: ${releaseSigningProblem()}; HOMECAST_REQUIRE_RELEASE_SIGNING is set.")
        }
        logger.warn("HomeCast: ${releaseSigningProblem()}; signing the release build with the debug key.")
    }
}

android {
    namespace = "io.github.yunuserdemsihhatli.homecast"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.yunuserdemsihhatli.homecast"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("HOMECAST_KEYSTORE_FILE")!!)
                storePassword = releaseSigning.getValue("HOMECAST_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("HOMECAST_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("HOMECAST_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)

    implementation(libs.okhttp)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.slf4j.nop)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
