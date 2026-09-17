# The Xray core is a Go library called through the gomobile JNI bridge (go.Seq) and
# reflection (libv2ray.*). Renaming/removing these breaks the native calls at runtime
# with no compile-time warning, so keep them untouched.
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**

# org.json is parsed dynamically by field name (optString/optJSONArray, etc.) all over
# the panel API and Xray config factory — nothing to strip here, just avoid warnings.
-dontwarn org.json.**

# OkHttp/Okio use some optional platform-specific classes not present on Android.
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil's image loading looks up decoders via reflection.
-dontwarn coil.**
