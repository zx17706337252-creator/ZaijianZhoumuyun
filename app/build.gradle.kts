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

        // 方案 8-12：Supabase ANON_KEY 通过 local.properties 注入，不再硬编码在源码中。
        // 使用方法：在项目根目录创建 local.properties（已加入 .gitignore），内容如下：
        //   SUPABASE_ANON_KEY=your_actual_anon_key
        // 未配置时使用占位符"PLACEHOLDER"，方便 CI 通过环境变量替换。see: https://github.com/zaijian/zhoumuyun/security
        val localProps = rootProject.file("local.properties")
        val supabaseAnonKey = if (localProps.exists()) {
            Properties().apply { load(localProps.inputStream()) }.getProperty("SUPABASE_ANON_KEY", "PLACEHOLDER")
        } else {
            "PLACEHOLDER"
        }
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")

        // S3问题3修复：SUPABASE_URL 与 ANON_KEY 一致，从 local.properties 注入
        // local.properties 中新增 SUPABASE_URL 配置项，未配置时回退默认值
        val supabaseUrl = if (localProps.exists()) {
            Properties().apply { load(localProps.inputStream()) }.getProperty("SUPABASE_URL", "https://npszynuzemkozojgnsvv.supabase.co")
        } else {
            "https://npszynuzemkozojgnsvv.supabase.co"
        }
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")

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

    // 编译错误修复：本地 JDK 为 21（Android Studio 自带 JBR）时，Kotlin 编译器
    // 默认跟随运行 Gradle 的 JDK 版本，与上面 compileOptions 显式指定的 Java 11
    // 不一致，导致 compileReleaseJavaWithJavac(11) 与 kspReleaseKotlin(21) 冲突。
    // 显式把 Kotlin 编译目标也钉在 11，与 Java 侧保持一致。
    // 【2026-07-21】kotlinOptions 已弃用，迁至 compilerOptions DSL


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

    // 批次0防复发：让 androidTest 的 MigrationTestHelper 能从 assets 读取 schema JSON。
    // Room 的 schemaLocation（ksp 配置）导出到 $projectDir/schemas，这里把它作为
    // androidTest 的 assets 源，MigrationTestHelper 通过 assets 打开 58.json/62.json。
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
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

    // W6-5 修复：数据层单元测试依赖
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)

    // 批次0防复发：Room 迁移插桩测试依赖。
    // MigrationTestHelper 需要 androidx.test:core 提供的 InstrumentationRegistry/Context，
    // 以及 androidx.test.ext:junit 的 AndroidJUnit4 runner。放在 androidTest 使其在
    // 真机/模拟器上运行（MigrationTestHelper 依赖 Android 框架的 SupportSQLiteOpenHelper）。
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
