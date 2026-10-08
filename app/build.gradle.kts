import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.lingxi"
    compileSdk = 34

    // Auto-increment version: read from version.properties (maintained by build script)
    val versionPropsFile = rootProject.file("version.properties")
    val (vCode, vName) = if (versionPropsFile.exists()) {
        val p = Properties().apply { load(versionPropsFile.inputStream()) }
        (p.getProperty("versionCode") ?: "1").toInt() to (p.getProperty("versionName") ?: "0.1.0")
    } else { 1 to "0.1.0" }

    defaultConfig {
        applicationId = "com.lingxi"
        minSdk = 26
        targetSdk = 34
        versionCode = vCode
        versionName = vName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        resourceConfigurations += listOf("en", "zh", "zh-rCN", "zh-rTW")

        lint {
            abortOnError = false
            checkReleaseBuilds = false
        }
    }

    // Keystore decoded from repo secrets at CI build time (signing.properties,
    // git-ignored). Missing/incomplete → unsigned release APK, build still passes.
    val signingPropsFile = rootProject.file("signing.properties")
    if (signingPropsFile.exists()) {
        val props = Properties().apply { load(signingPropsFile.inputStream()) }
        val storeFile = props.getProperty("STORE_FILE")
        val storePassword = props.getProperty("STORE_PASSWORD")
        val keyAlias = props.getProperty("KEY_ALIAS")
        val keyPassword = props.getProperty("KEY_PASSWORD")
        if (storeFile != null && storePassword != null && keyAlias != null && keyPassword != null) {
            signingConfigs {
                create("release") {
                    this.storeFile = rootProject.file(storeFile)
                    this.storePassword = storePassword
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPassword
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (signingPropsFile.exists()) {
                val props = Properties().apply { load(signingPropsFile.inputStream()) }
                if (props.getProperty("STORE_FILE") != null) {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)

    // DI (Hilt) — PRD 技术栈
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Networking — R1 多 Provider LLM 客户端基础
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // F18: API Key 加密存储（AndroidKeyStore + EncryptedSharedPreferences）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
