import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

// ── Supabase 配置注入修复 ─────────────────────────────────────
// 此前用 project.findProperty() 读取 SUPABASE_URL / SUPABASE_ANON_KEY，
// 但 findProperty 只读 gradle.properties / -P / 环境变量，不读 local.properties，
// 与下方注释"在 local.properties 配置 SUPABASE_ANON_KEY，构建时自动注入"的说明不符，
// 导致 BuildConfig.SUPABASE_URL 恒为空 → SupabaseClient 拼接出无协议 URL
// （MalformedURLException: no protocol）。现改为：优先读 local.properties，回退到 -P/gradle.properties。
fun loadLocalProperties(): Properties {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { props.load(it) }
    }
    return props
}

fun supabaseProp(key: String): String {
    val local = loadLocalProperties().getProperty(key)
    val value = (local ?: project.findProperty(key)?.toString() ?: "").trim()
    // P1-1 修复：此前值为空时静默生成空字符串 BuildConfig 常量，编译能过、
    // 但 SupabaseClient.openConnection() 的 require(base.isNotEmpty()) 在运行时
    // 抛异常，又被各调用方（云同步/补偿/Token 上传）的 try-catch 悄悄吞掉，
    // 用户端表现为"云同步好像从没生效过"且无任何排查线索。改为在构建期
    // 就把这个问题打成显眼的 warning，让"配置缺失"在编译日志阶段就可见，
    // 而不是留到运行时一路静默失败到最后一环。
    if (value.isEmpty()) {
        project.logger.warn(
            "⚠️  [Supabase 配置缺失] 未找到 $key（已检查 local.properties 与 " +
            "-P/gradle.properties）。BuildConfig.$key 将被编译为空字符串，" +
            "云同步/本地补偿/推送 Token 上传等功能会在运行时静默失效。" +
            "请在项目根目录 local.properties 中添加：$key=<你的值>"
        )
    }
    return value
}
// ──────────────────────────────────────────────────────────────

android {
    namespace = "com.zaijian.zhoumuyun"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zaijian.zhoumuyun"
        minSdk = 26
        targetSdk = 34
        versionCode = 148
        versionName = "1.4.8"

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "SUPABASE_URL", "\"${supabaseProp("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseProp("SUPABASE_ANON_KEY")}\"")

        // M1 修复：12 个迁移测试全部用 @RunWith(AndroidJUnit4::class)（androidx.test），
        // 但此前未配置 testInstrumentationRunner，AGP 默认走旧版 android.test.InstrumentationTestRunner，
        // 导致 instrumentation 进程崩溃（Process crashed）。必须用 androidx.test.runner.AndroidJUnitRunner。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 任务2（P0-3 特征化测试）测试基础设施：纯 JVM 单测中 android.util.* 是"未 mock"桩，
    // 调用即抛 RuntimeException（"Method X in android.util.Log not mocked"）——生产代码里
    // 大量 ZLog.e/w 走 android.util.Log，会击穿 sendMessage 协程（如场景④ ImpersonationStateStore
    // catch 内 ZLog.e）。mockkStatic 无法 mock 这些 final stub 类，故开启 isReturnDefaultValues，
    // 让 android.util.* 方法返回默认值（Log.e→0）而非抛异常。对齐 M1 补 room-testing 的先例。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/NOTICE"
        }
    }

    lint {
        abortOnError = false
    }

    sourceSets {
        // M1 修复：MigrationTestHelper 从 assets 目录读取 schema 快照
        // （com.zaijian.zhoumuyun.data.db.AppDatabase/<version>.json），
        // 必须把 room.schemaLocation 导出的 schemas/ 目录打包进 androidTest assets。
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}

// Room schema 导出目录：MigrationTestHelper 依赖 schemas/<version>.json
// 运行全链 validate 子测试（runMigrationsAndValidate）时必须存在。
// 补配置后仅在编译时生成当前数据库版本（v80）的快照，历史版本快照无法补全（Room 机制限制）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    implementation("io.coil-kt:coil-compose:2.6.0")

    // Markwon
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")

    // Apache POI
    implementation("org.apache.poi:poi-ooxml:5.2.5") {
        exclude("org.apache.xmlbeans", "xmlbeans")
    }
    implementation("org.apache.xmlbeans:xmlbeans:5.1.1")

    // PDFBox-Android
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // ExifInterface
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // JavaMail
    implementation("com.sun.mail:jakarta.mail:2.0.1")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    implementation("org.json:json:20240303")

    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.7")

    testImplementation("junit:junit:4.13.2")
    // 测试基础设施修复（2026-08-04 恢复会话发现）：原始包自带的部分纯 JVM 单测
    // （SkillRegistryTest/SkillToolsTest 等）引用了 kotlinx-coroutines-test 与
    // androidx.test:core，但 testImplementation 未声明，导致整个 test 源集编译失败
    // （连带阻塞所有单测运行）。二者已在版本目录中定义，此处补接线。
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    // 任务2（P0-3 特征化测试）：sendMessage 内 ProviderManager.instance / AppContainer.instance
    // 是硬编码单例（无构造注入口），手写 Fake 结构性无法解决；用 MockK 的 companion-object mock
    // 精准 mock 这两个单例 + 构造注入的叶子 repo。仅 test scope，不进正式包。
    testImplementation("io.mockk:mockk:1.13.13")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // M1 修复：12 个迁移测试全部依赖 androidx.room.testing.MigrationTestHelper，
    // 此前缺 room-testing 依赖导致 androidTest 编译失败（migration 测试无法运行）。
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}