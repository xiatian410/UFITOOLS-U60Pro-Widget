# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Jsoup
-keep class org.jsoup.** { *; }

# JSpecify annotations (jsoup 依赖)
-dontwarn org.jspecify.annotations.**
-keep class org.jspecify.annotations.** { *; }

# Kotlin serialization (if used in future)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
