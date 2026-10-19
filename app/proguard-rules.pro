# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }

# Ktor / 协程
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# 应用 DTO
-keep class com.atun.componentvault.**$$serializer { *; }
-keepclassmembers class com.atun.componentvault.** {
    *** Companion;
}
