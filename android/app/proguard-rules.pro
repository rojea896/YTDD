# youtubedl-android: reflection-heavy (Jackson parses the GitHub release JSON and VideoInfo).
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn java.beans.**

# Apache Commons Compress (used by youtubedl-android to unpack Python on first launch) creates its
# zip extra-field classes by reflection; stripped constructors crash init ("not a concrete class").
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
