# TopNTown DMS ProGuard Rules

# Supabase / Ktor
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.topntown.dms.**$$serializer { *; }
-keepclassmembers class com.topntown.dms.** { *** Companion; }
-keepclasseswithmembers class com.topntown.dms.** { kotlinx.serialization.KSerializer serializer(...); }
