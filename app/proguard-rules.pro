# Add project specific ProGuard rules here.

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }

# Keep osmdroid
-keep class org.osmdroid.** { *; }
