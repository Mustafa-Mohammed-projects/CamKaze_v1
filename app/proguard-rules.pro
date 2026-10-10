# R8 full mode is on by default; libraries ship their own consumer rules.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
