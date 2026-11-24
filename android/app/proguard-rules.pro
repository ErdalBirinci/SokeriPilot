# Car App Library serializes template models across the host boundary.
-keep class androidx.car.app.** { *; }
-dontwarn androidx.car.app.**

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
