import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigningProperties = Properties()
val releaseSigningPropertiesFile = rootProject.file("release-signing.properties")
if (releaseSigningPropertiesFile.exists()) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}

fun signingValue(property: String, environment: String): String =
    System.getenv(environment)?.takeIf(String::isNotBlank)
        ?: releaseSigningProperties.getProperty(property, "").trim()

val releaseStoreFile = signingValue("storeFile", "ARRPILOT_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "ARRPILOT_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "ARRPILOT_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "ARRPILOT_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all(String::isNotBlank)

android {
    namespace = "io.github.crunchyinmilk.arrpilot"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.crunchyinmilk.arrpilot"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.1"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
}

val verifyReleaseSigningConfigured = tasks.register("verifyReleaseSigningConfigured") {
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Provide release-signing.properties or the ARRPILOT_KEYSTORE_* environment variables."
        }
        check(rootProject.file(releaseStoreFile).isFile) {
            "Release signing keystore was not found at $releaseStoreFile"
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigningConfigured)
}
