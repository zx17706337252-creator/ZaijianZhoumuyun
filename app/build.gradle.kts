plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // Phase 30 方案六：Firebase google-services
    alias(libs.plugins.google.services)
}

import java.util.Properties

android {
    namespace = "com.zaijian.zhoumuyun"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zaijian.zhoumuyun"
        minSdk = 26
        targetSdk = 35
        // 依赖12 修复：版本号从 gradle.properties 注入，CI 可通过 -P 参数覆盖
        // 依赖9 修复：兜底默认值同步为当前实际版本，避免属性缺失时静默构建出
        // 版本号倒退的包（此前默认值为 "7"/"0.28.0"，与实际版本相差甚远）。
        // 同时移除了内容损坏、版本号更旧的多余 app/gradle.properties。
        versionCode = (project.findProperty("appVersionCode") as String? ?: "58").toInt()
        versionName = project.findProperty("appVersionName") as String? ?: "0.56.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 依赖6 修复：release 签名配置，从 keystore.properties 读取，不硬编码密钥信息。
    // 使用方法：在项目根目录新建 keystore.properties（已加入 .gitignore），内容如下：
    //   storeFile=../your_keystore.jks
    //   storePassword=your_store_password
    //   keyAlias=your_key_alias
    //   keyPassword=your_key_password
    val keystorePropsFile = rootProject.file("keystore.properties")
    val hasKeystore = keystorePropsFile.exists()
    if (hasKeystore) {
        val keystoreProps = Properties().also { it.load(keystorePropsFile.inputStream()) }
        signingConfigs {
            create("release") {
                storeFile     = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias      = keystoreProps["keyAlias"] as String
                keyPassword   = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true   // 依赖8 修复：启用资源缩减
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // keystore.properties 存在时才引用签名配置，避免 CI 无密钥时构建失败
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose) // P1-11-2：collectAsStateWithLifecycle
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Security (EncryptedSharedPreferences for API keys)
    implementation(libs.androidx.security.crypto)

    // WorkManager (后台任务)
    // 依赖7 修复：版本号已收进 libs.versions.toml（workManager）
    implementation(libs.androidx.work.runtime.ktx)

    // DataStore (preferences)
    implementation(libs.androidx.datastore.preferences)

    // Phase 21: Markwon Markdown 渲染
    implementation(libs.markwon.core)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.tasklist)

    debugImplementation(libs.androidx.ui.tooling)

    // Phase 28 Part 2 — Apache POI（excel_gen + pptx_gen 共享）
    // poi-ooxml 5.4.0（修复 CVE-2025-31672，原 5.2.5 受影响）包含 xmlbeans，需在 proguard-rules.pro 追加混淆排除
    // 依赖10 修复：版本号已收进 libs.versions.toml（poiOoxml）
    implementation(libs.poi.ooxml)

    // Phase 30 方案六 — Firebase Cloud Messaging（FCM 离屏推送）
    // 依赖7 修复：版本号已收进 libs.versions.toml（firebaseBom）
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging.ktx)

    // 邮件收发 — javax.mail 安卓移植版（SMTP 发信 + IMAP 收信）
    // 依赖7 修复：版本号已收进 libs.versions.toml（androidMail）
    // 依赖10 注：android-mail 1.6.7 为旧 javax.mail 命名空间，迁移 jakarta.mail 纳入后续计划
    implementation(libs.android.mail)
}
