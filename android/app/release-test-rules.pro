# AndroidJUnitRunner shares these target-APK dependencies but calls their original
# JVM APIs. Preserve only the test harness boundary, not app or Markwon internals.
# Production release rules are unchanged.
-keep class androidx.tracing.Trace { *; }
-keep class kotlin.** { *; }
