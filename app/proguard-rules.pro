# HomeCast ProGuard / R8 rules.
#
# kotlinx.serialization, OkHttp, Ktor and androidx.webkit ship their own consumer rules;
# only what those rules do not cover is listed here.

# Ktor's IntelliJ debugger detector references JVM management APIs that do not exist on
# Android. It is only called when a debugger is attached to a JVM, never on device.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
