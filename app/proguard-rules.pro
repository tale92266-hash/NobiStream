# NobiStream ProGuard rules. Release builds are unshrunk (minifyEnabled=false),
# these rules only matter if shrinking is ever turned on.
-keep class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**
-keep class com.nobi.nobistream.** { *; }
