# No reflection is used; default optimised rules are enough.
# Strip all logging calls from release builds.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# junrar logs through slf4j; with no binding on Android it is a no-op.
-dontwarn org.slf4j.**
-dontwarn com.github.junrar.**
