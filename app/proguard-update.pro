# ===== 自动更新模块：防止被 R8 裁剪/混淆 =====
-keep class com.github.tvbox.osc.update.** { *; }
-keepclassmembers class com.github.tvbox.osc.update.** { *; }

# BuildConfig 字段是用反射读取的，必须保留，否则 token / 仓库配置读不到
-keepclassmembers class com.github.tvbox.osc.BuildConfig {
    public static <fields>;
}
