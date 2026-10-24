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

# ---------------------------------------------------------------- 内置 SSH 服务
# Apache MINA SSHD 大量使用反射与 ServiceLoader（加密 provider、IO 实现、SFTP 子系统），
# R8 把它们裁掉就会在真机上「连不上但看不出原因」，所以整包保留。
-keep class org.apache.sshd.** { *; }
-keep interface org.apache.sshd.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-keepclassmembers class * implements org.apache.sshd.common.util.security.SecurityProviderRegistrar { *; }
-keep class org.slf4j.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
-dontwarn org.apache.sshd.**
-dontwarn net.i2p.crypto.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
