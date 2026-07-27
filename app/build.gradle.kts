import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 读取 local.properties 中的 Supabase 配置（开发环境用法，与 SupabaseClient.kt
// 顶部注释描述的注入方式保持一致）。文件不存在或缺少某个 key 时不报错，
// 留给下面的 CI 参数 / 环境变量兜底。
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

fun secretProperty(name: String): String =
    (localProperties.getProperty(name)
        ?: project.findProperty(name) as String?
        ?: System.getenv(name)
        ?: "")

android {
    namespace = "com.zaijian.zhoumuyun"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zaijian.zhoumuyun"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 修复：BuildConfig.SUPABASE_URL / SUPABASE_ANON_KEY / DEBUG 编译报错
        // ——原先 buildFeatures 未开启 buildConfig，且这两个自定义字段从未声明。
        // 读取顺序：local.properties（开发环境）→ -P 命令行参数 → 环境变量（CI），
        // 三者都没有则为空字符串，不硬编码真实密钥到仓库里。
        buildConfigField("String", "SUPABASE_URL", "\"${secretProperty("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secretProperty("SUPABASE_ANON_KEY")}\"")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 修复：BuildConfig 类未生成导致 Unresolved reference: BuildConfig
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
	    excludes += "/META-INF/LICENSE.md"
	    excludes += "/META-INF/NOTICE.md"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 修复：ripple() 未解析——2024.04.00 对应的 material3 版本（约1.2.x）还没有
    // androidx.compose.material3.ripple 里的公开 ripple() API（该 API 在 material3 1.3.0
    // 才稳定发布）。升级到 2024.09.00（material3 1.3.0）解决，不改动其他既有行为。
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
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

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

    // JavaMail（Jakarta 命名空间：代码内 import 已同步改为 jakarta.mail.*）
    implementation("com.sun.mail:jakarta.mail:2.0.1")

    // Firebase
    implementation("com.google.firebase:firebase-messaging-ktx:24.0.0")

    implementation("org.json:json:20240303")

    // 修复：kotlinx.collections.immutable.* 全面 Unresolved reference
    // （ImmutableList/persistentListOf 等），此前项目里大量使用但从未声明该依赖，
    // 导致 RoundtableViewModel 的 uiState 相关类型退化，连带 RoundtableScreen.kt
    // 里 itemsIndexed/associateBy 等处的 lambda 参数类型推断失败（"it" 无法解析、
    // 重载解析歧义）。
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.7")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
