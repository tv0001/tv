-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WorkManager creates its database class by reflection (WorkDatabase_Impl()), and R8 cannot see that call.
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# smbj（网络存储的 SMB）：内部事件总线 mbassador 靠反射找带注解的处理方法（bouncycastle 的算法是直接 new 的，不用保留）
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.slf4j.**
-dontwarn javax.el.**
