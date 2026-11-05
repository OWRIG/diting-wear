import java.util.Properties

// 服务器地址和手机 token 从仓库外的 .secrets/mailbox.env 读取（不提交到 git）
val secrets = Properties().apply {
    val f = rootProject.file("../.secrets/mailbox.env")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.owrig.diting"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.owrig.diting"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"${secrets.getProperty("SERVER_URL", "")}\"")
        buildConfigField("String", "DEFAULT_PHONE_TOKEN", "\"${secrets.getProperty("PHONE_TOKEN", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 先用 debug 签名，方便直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
