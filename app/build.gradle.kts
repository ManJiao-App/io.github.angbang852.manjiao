import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.angbang852.manjiao"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.angbang852.manjiao"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    // ★ 签名密钥出库：密码此前明文提交在仓库中（git 历史已暴露，建议尽快 rotate）。
    // 现从 local.properties 读取（该文件在 .gitignore 内），需包含：
    //   manjiao.storeFile=D:/path/to/release-manjiao.keystore
    //   manjiao.storePassword=xxx
    //   manjiao.keyAlias=manjiao
    //   manjiao.keyPassword=xxx
    // keystore 缺失时 release 构建产出 unsigned APK（不再因路径失效直接报错）
    val signingProps = Properties().apply {
        val f = rootProject.file("local.properties")
        // UTF-8 Reader：storeFile 路径含中文，Properties.load(InputStream) 默认
        // ISO-8859-1 会乱码导致 exists() 恒 false
        if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
    }
    signingConfigs {
        create("release") {
            val sf = signingProps.getProperty("manjiao.storeFile") ?: ""
            if (sf.isNotEmpty() && file(sf).exists()) {
                storeFile = file(sf)
                storePassword = signingProps.getProperty("manjiao.storePassword") ?: ""
                keyAlias = signingProps.getProperty("manjiao.keyAlias") ?: "manjiao"
                keyPassword = signingProps.getProperty("manjiao.keyPassword") ?: ""
            }
        }
    }
    buildTypes {
        release {
            // ★ R8 开启：Xposed 模块按名反射点已由 proguard-rules.pro 保护
            //（Module 入口 / libxposed API / JNI native 方法）
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    compileOnly(files("libs/libxposed-api-102.0.0.jar"))
    implementation(files("libs/libxposed-interface-102.0.0.jar"))
    implementation(files("libs/libxposed-service-102.0.0.jar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
}