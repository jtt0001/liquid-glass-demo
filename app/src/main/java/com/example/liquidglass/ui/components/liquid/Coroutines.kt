/*
 * 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0，仓库根 LICENSE）
 *   源文件：app/src/commonMain/kotlin/.../catalog/utils/Coroutines.kt（expect）
 *          + app/src/androidMain/kotlin/.../catalog/utils/Coroutines.kt（actual，
 *            实现 = `kotlinx.coroutines.android.awaitFrame`）
 *   上游 HEAD：65ab177（kmp 分支）
 * 本工程改动（适配差异清单 D5）：
 *   · 本工程是纯 Android 单模块 ⇒ 把 expect/actual 收敛成一个普通函数（同为"等下一帧"语义）；
 *   · 实现改用 Compose 自己的帧时钟 [withFrameNanos]（与 kotlinx.coroutines.android.awaitFrame
 *     同为"等下一帧"），避免为一个 3 行工具函数引入/依赖 kotlinx-coroutines-android 的 android 包
 *     （CMP 侧与 AndroidX 侧混用的历史坑）。
 *     调用点上下文 = rememberCoroutineScope()（AndroidUiDispatcher，自带 MonotonicFrameClock）✓
 */
package com.example.liquidglass.ui.components.liquid

import androidx.compose.runtime.withFrameNanos

/** 等下一帧（上游 utils/Coroutines.kt 的 Android 实现等价物）。 */
internal suspend fun awaitFrame() {
    withFrameNanos { }
}
