// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // Phase 30 方案六：Firebase google-services 插件
    // 依赖11 修复：版本号已收进 libs.versions.toml（googleServicesPlugin）
    alias(libs.plugins.google.services) apply false
}
