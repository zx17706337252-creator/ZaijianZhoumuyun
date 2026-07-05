# ─────────────────────────────────────────────────────────────────────────────
# 依赖9 · 依赖8 修复：补全通用保留规则（原只有全量 keep，无精确规则）
#
# 注：-keep class com.zaijian.zhoumuyun.** { *; } 已移除（阶段1 安全修复 H-3）。
#     下面仅保留 R8 真正需要的精确规则。
# ─────────────────────────────────────────────────────────────────────────────

# ── 通用属性保留（泛型、注解、内部类等反射/序列化场景必须）──────────────────
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes SourceFile,LineNumberTable   # 保留堆栈行号，方便 Crashlytics 解析

# ── Kotlin 元数据（反射 / @Serializable / data class copy 等依赖）────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.jvm.JvmStatic *;
    @kotlin.jvm.JvmField *;
}

# ── Room 实体（KSP 生成代码依赖无参构造）────────────────────────────────────
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static ** getInstance(...);
}

# ── Compose（Composable 函数名保留，Preview 相关）────────────────────────────
-keep @androidx.compose.runtime.Composable class *
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ── Phase 28 — Apache POI（excel_gen + pptx_gen）────────────────────────────
# poi-ooxml / xmlbeans 内部大量反射加载 SchemaType / ContentHandler，需全量保留
-keep class org.apache.poi.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class schemasMicrosoftComOfficeOffice.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.openxmlformats.**
-dontwarn org.apache.xmlbeans.**

# POI 传递依赖（log4j、osgi、bnd、findbugs）在 Android 上不存在，仅需忽略
-dontwarn aQute.bnd.**
-dontwarn edu.umd.cs.findbugs.**
-dontwarn org.osgi.**
-dontwarn java.awt.**
-dontwarn com.graphbuilder.**

# ── 邮件收发 — com.sun.mail:android-mail / android-activation ────────────────
# javax.mail 内部大量反射查找 Provider/Handler 实现类，混淆会破坏 SMTP/IMAP 连接
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.mail.** { *; }
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn com.sun.mail.**

# ── Firebase（FCM 消息处理服务）──────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
