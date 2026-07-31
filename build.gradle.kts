plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
    // A13-1 修复：google-services 插件 classpath 声明
    id("com.google.gms.google-services") version "4.4.2" apply false
}