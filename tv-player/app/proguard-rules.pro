-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WorkManager creates its database class by reflection (WorkDatabase_Impl()), and R8 cannot see that call.
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
