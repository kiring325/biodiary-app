import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 接続先の URL は Git に含めない local.properties に書く。例: biodiary.apiUrl=https://....workers.dev
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val biodiaryApiUrl = (localProperties.getProperty("biodiary.apiUrl")
    ?: providers.gradleProperty("biodiary.apiUrl").orNull ?: "").trim().trimEnd('/')
if (biodiaryApiUrl.isEmpty()) {
    logger.warn("local.properties に biodiary.apiUrl がありません。アプリの接続先設定で URL を入力する必要があります。")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "jp.morinote.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.morinote.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3"
        buildConfigField("String", "BIODIARY_API_URL", "\"${biodiaryApiUrl.replace("\\", "").replace("\"", "")}\"")
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.webkit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
