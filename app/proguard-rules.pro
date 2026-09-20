# Canta keeps its ProGuard rules intentionally small: the app has no reflection
# beyond org.json, Media3's own rules ship with the library, and Jsoup pulls in
# nothing reflective in the code paths this app uses.

# Keep the Media3 download service referenced from the manifest by name.
-keep class com.jcversa.canta.manager.CantaDownloadService { *; }

# Jsoup uses reflection-free DOM access, but its signature classes are worth
# keeping if a future release adds reflective node handling.
-dontwarn org.jsoup.**

# OkHttp / Okio ship their own consumer rules; silence the optional-platform
# warnings that otherwise fail a release build.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
