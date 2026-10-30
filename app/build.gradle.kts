plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.formbot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.formbot.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.1"
        // TDLib — нативная библиотека; на Android 16 реальные телефоны — только arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Личное приложение: подписываем отладочным ключом, чтобы APK сразу ставился.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Сквозной тест с отправкой — только по явной ссылке: ./gradlew testDebugUnitTest -Pe2eForm=<тестовая форма>
        unitTests.all { it.systemProperty("e2eForm", project.findProperty("e2eForm") ?: "") }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    // TDLib 1.8.67 (org.drinkless.tdlib.Client / TdApi) — вход в Telegram под своим аккаунтом.
    implementation("io.github.tdlib-android:core:0.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
