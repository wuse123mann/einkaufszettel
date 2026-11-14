# ML Kit bringt eigene Regeln mit. Hier nur zur Sicherheit:
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

-keep class org.chromium.net.** { *; }
-dontwarn org.chromium.net.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
