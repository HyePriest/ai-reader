# PDFBox-Android loads fonts, cmaps and filters by name, and uses reflection for some of them.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
# BouncyCastle is only reached for encrypted PDFs; its optional providers reference classes that are not on Android.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
# jsoup's optional re2j regex engine is not bundled.
-dontwarn com.google.re2j.**
# The native e-book importer is looked up from C by its JNI name.
-keepclasseswithmembernames class * { native <methods>; }
