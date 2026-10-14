# LiquidGlassDemo ProGuard 规则
# AGSL Shader 源码是编译期字符串常量，不涉及反射，无需额外 keep。
# Compose 与 Kotlin 标准库规则由 AGP 默认模板提供。

# 保留 Backdrop 库（如启用混淆时）——库自身为公开 API，无反射需求
-keep class com.kyant.backdrop.** { *; }
