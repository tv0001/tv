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

# TVBox jar 爬虫（见 core/SpiderHost.kt）：jar 在运行时按原名调用 catvod 接口和宿主带的 OkHttp、okio、gson。
# 库只保留 jar 会用到的公开 API（顶层包和实际用到的子包，含受保护成员：jar 会写 new TypeToken<…>(){}），
# 内部实现照常裁剪、改名——整个库都留着会让安装包大三四百 KB。新 jar 用到别的子包时，`dexdump -a -d` 看一下再加
# （要带 -a：jar 的数据类靠 @SerializedName 注解映射字段名，注解引用不出现在代码里）。
-keep class com.github.catvod.** { *; }
-keep public class okhttp3.* { public protected *; }
-keep public class okhttp3.internal.Util { public protected *; }
-keep public class okio.* { public protected *; }
-keep public class com.google.gson.* { public protected *; }
-keep public class com.google.gson.reflect.* { public protected *; }
-keep public class com.google.gson.stream.* { public protected *; }
-keep class com.google.gson.annotations.* { *; }
-keep class androidx.core.content.FileProvider { public static *; }
