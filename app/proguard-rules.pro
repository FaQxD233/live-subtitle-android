# ProGuard rules for LiveBuddy Android release builds.
#
# R8 keeps manifest-declared components (Activities, Services,
# Application) automatically, and no reflection is used in app code,
# so an app-wide -keep is unnecessary and would defeat minification.

# OkHttp ships its own consumer rules; only suppress warnings here.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# org.json (standalone dependency without consumer rules)
-dontwarn org.json.**
-keep class org.json.** { *; }
