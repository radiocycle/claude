# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.radiocycle.llmhub.** {
    *** Companion;
}
-keepclasseswithmembers class dev.radiocycle.llmhub.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.radiocycle.llmhub.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JS bridge used by the exec_js tool
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
