# WorkManager and Room discover the generated database implementation through
# reflection during AndroidX Startup. R8 otherwise removes its no-arg
# constructor from minified release builds, causing startup to crash.
-keep class androidx.work.impl.WorkDatabase_Impl {
    <init>(...);
}
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
