# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.xtt.mcpbox.**$$serializer { *; }
-keepclassmembers class com.xtt.mcpbox.** {
    *** Companion;
}
-keepclasseswithmembers class com.xtt.mcpbox.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 服务器核心（HTTP / MCP / 工具）不做混淆，避免反射/序列化问题 ----
-keep class com.xtt.mcpbox.core.** { *; }
-keep class com.xtt.mcpbox.server.** { *; }

# ---- Shizuku ----
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
-dontwarn android.app.**
-dontwarn android.os.**

# ---- Kotlin 元数据 ----
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**
