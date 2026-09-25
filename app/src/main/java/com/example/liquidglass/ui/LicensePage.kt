package com.example.liquidglass.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.liquidglass.BuildConfig
import com.example.liquidglass.R
import com.example.liquidglass.debug.AppDebugLog
import com.example.liquidglass.debug.DebugSwitches

/**
 * ==================== 二级菜单④「开源许可与致谢」（P29）====================
 *
 * 需求（用户点名）：控制中心 →「更多设置」底部新增一节「关于」，里面一行「开源许可与致谢 ›」
 * 进入第三个（页面机制上第四个分页，前三个是「更多设置 / 图片编辑 / 组件演示」，同一套页机制）
 * 二级页：逐条列出第三方来源的【项目名 + 版权行 + 许可证名 + 链接】。
 *
 * 逐字纪律（本页最容易出错的地方）：
 *   · 版权行与许可声明/全文【一律】取自 [LicenseTexts]（脚本从上游 LICENSE 逐字生成、逐字节校验）
 *     ⇒ 本文件【不手打任何版权年/授权语】（手打就一定会出现"记忆里的年份"✗）。
 *   · 版权行还是【从逐字文本里抽出来的】（[LicenseItem.copyright] 由 notice 派生，见下）
 *     ⇒ "页面上的版权行"与"上游 LICENSE 里的那一行"在代码层面同源，不可能对不上 ✓
 *   · 平台/框架（AndroidX·Compose / Kotlin / CMP / AGP）按行业案例只写名称 + 用途 + 许可证名 + 链接，
 *     不复制其文本（页内注明"完整许可证文本见链接"）✓
 *
 * 交互（点按打开 / 长按复制，二选一 —— 这里两个都给了，理由）：
 *   · 点按【整行链接】= 走官方 `Intent(ACTION_VIEW)` 交给系统浏览器/应用 ⇒ 上游 LICENSE 全文随时可查；
 *   · 长按同一行 = 复制 URL 到剪贴板（Toast 反馈）⇒ 控制中心里"分享/记录来源"的常见诉求有着落，
 *     且不依赖本机是否装了浏览器（模拟器/精简机没浏览器时点按只会降级成 Toast，复制仍可用）。
 *   · ✗ 不把整张卡做成可点：面板里的主要手势是纵向拖动，整卡可点会把"拖动时手指落点"误判成点击。
 */

// ---- 页面配色：跟随【当前调色板】（亮色档 = 上面注释里那组既有取值逐字不变 ✓；
//      深色档 = GlassTheme.DarkGlassPalette 的高对比浅字/浅容器）----
// 【深色/亮色模式·2026-09-24】原为 6 个固定 private val，改为按调色板取值的 @Composable getter。
private val LicTitleColor: Color
    @Composable get() = glassPalette.scheme.onSurface        // 亮色档 = 0xFF0E1626（逐字不变）
private val LicBodyColor: Color
    @Composable get() = glassPalette.scheme.onSurfaceVariant // 亮色档 = 0xFF23304A（逐字不变）
private val LicHintColor: Color
    @Composable get() = glassPalette.hintText                // 亮色档 = 0xFF4A5A78（逐字不变）
private val LicAccentColor: Color
    @Composable get() = glassPalette.scheme.primary          // 亮色档 = 0xFF1B4A8F（逐字不变）
private val LicCardBg: Color
    @Composable get() = glassPalette.scheme.surfaceVariant   // 亮色档 = 0x1A0E1626（逐字不变）
private val LicCodeBg: Color
    @Composable get() = glassPalette.chipContainer           // 亮色档 = 0x140E1626（逐字不变）

/** 面板底色开关跟随既有约定：[DebugSwitches.panelEdgeAa]（与「恢复默认设置」行逐字同一套写法）。 */
@Composable
private fun licenseRowBg(): Modifier =
    if (DebugSwitches.panelEdgeAa) Modifier.background(LicCardBg, shape = PanelChipShape)
    else Modifier.background(LicCardBg)

/**
 * 一条第三方来源。字段与验收要求一一对应：项目名 / 版权行 / 许可证名 / 链接（+ 用途与版本）。
 *
 * [copyright] 是【派生】的：从 [notice]（逐字 LICENSE 文本）里抽出第一行 "Copyright …"
 * ⇒ 页面上显示的版权行逐字等于上游 LICENSE 里的那一行，人工不可能写错年份 ✓
 * （[notice] 为空 = 平台/框架类，未复制其文本 ⇒ 页面显示"未复制文本，见链接"）。
 */
internal class LicenseItem(
    /** 项目名（上游仓库全名）。 */
    val name: String,
    /** 本工程所用版本 / 坐标（来源：app/build.gradle.kts + gradle/libs.versions.toml）。 */
    val version: String,
    /** 用途一句（写进页面的"为什么致谢它"）。 */
    val usage: String,
    /** 许可证名（照上游 LICENSE 的题名）。 */
    val licenseName: String,
    /** 上游链接（点按打开 / 长按复制）。 */
    val url: String,
    /** 许可以及相关文本（逐字照录自上游 LICENSE；平台组件为空串）。 */
    val notice: String = ""
) {
    /** 版权行：逐字取自 [notice]（"Copyright" 开头的那一行），页面显示时去掉行首缩进。 */
    val copyright: String =
        notice.lineSequence().firstOrNull { it.trimStart().startsWith("Copyright") }?.trim().orEmpty()
}

/**
 * 「玻璃与形状」：本工程直接使用（vendored 源码模块 / Maven 依赖 / 光学模型移植来源）的三个来源库。
 * 三份版权行与许可证全文都逐字取自各自仓库的 LICENSE（[LicenseTexts]）。
 */
private val GlassLicenseItems: List<LicenseItem> = listOf(
    LicenseItem(
        name = "Kyant0 / AndroidLiquidGlass（Backdrop）",
        version = "Backdrop 2.0.0 · vendored 源码模块 :backdrop（上游 commit bebb11a）",
        usage = "用途：背景捕获层与 drawBackdrop 玻璃基础设施 —— 面板/卡片玻璃的采样与绘制底座。",
        licenseName = "Apache License, Version 2.0",
        url = "https://github.com/Kyant0/AndroidLiquidGlass",
        notice = LicenseTexts.BACKDROP_NOTICE
    ),
    LicenseItem(
        name = "Kyant0 / Shapes",
        version = "io.github.kyant0:shapes:1.2.1（Maven 依赖）",
        usage = "用途：iOS 风格形状（Capsule / RoundedRectangularShape 等）—— 上游组件与本工程胶囊、卡片圆角取形。",
        licenseName = "Apache License, Version 2.0",
        url = "https://github.com/Kyant0/Shapes",
        notice = LicenseTexts.SHAPES_NOTICE
    ),
    LicenseItem(
        name = "QWEA0 / Liquid-Glass-Android（pandadog）",
        version = "光学模型移植来源（AGSL 折射/色散/边缘光照）",
        usage = "用途：玻璃光学模型（逆幂衰减折射剖面、色散、边缘光照、触点凸起）移植到本工程 AGSL 管线。",
        licenseName = "MIT License",
        url = "https://github.com/QWEA0/Liquid-Glass-Android",
        notice = LicenseTexts.QWEA_MIT
    )
)

/**
 * 「平台与框架」：按行业案例（Android/Compose 应用的开源致谢页）只写名称 + 用途 + 许可证名 + 链接，
 * ✗ 不复制其许可证文本（页内注明"完整许可证文本见链接"）。许可证名取自构建元数据：
 * Gradle 模块 POM 的 <licenses> 段（androidx / kotlin / jetbrains.compose / AGP 四者均为
 * "The Apache Software License, Version 2.0"）。
 */
private val PlatformLicenseItems: List<LicenseItem> = listOf(
    LicenseItem(
        name = "AndroidX / Jetpack Compose",
        version = "Compose BOM 2026.08.00 · activity-compose 1.13.0 · core-ktx 1.18.0",
        usage = "用途：Compose UI / 基础组件 / 运行时与 Android 支持库（玻璃面板、列表、动画都由它们承载）。",
        licenseName = "Apache License, Version 2.0",
        url = "https://github.com/androidx/androidx"
    ),
    // 【2026-09-18 合规补齐】原页面只笼统写「AndroidX / Jetpack Compose」，未点名下列直接/传递依赖；
    // 公开前的合规审查（~/Downloads/LG-compliance/REPORT.md 漏项③）要求逐项点名 ⇒ 单列一条 ✓
    LicenseItem(
        name = "AndroidX / Kotlin 其它直接与传递依赖",
        version = "metrics-performance 1.0.0 · graphics-path 1.0.1 · annotations 26.1.0 · " +
            "kotlinx-serialization-core 1.7.3 · lifecycle 系列 · savedstate · window · profileinstaller",
        usage = "用途：性能指标采集、矢量路径渲染（libandroidx.graphics.path.so）、注解、序列化与生命周期/窗口支持。" +
            "许可证与上一条相同（Apache License, Version 2.0），完整文本见上条链接与随包 META-INF 内的 LICENSE.txt。",
        licenseName = "Apache License, Version 2.0",
        url = "https://github.com/androidx/androidx"
    ),
    // 【2026-09-18 合规补齐】自适应图标与图形由作者自绘/自写，壁纸来自 Unsplash（Unsplash License，可商用无需署名）✓
    LicenseItem(
        name = "图片素材：内置壁纸",
        version = "4 张（云海日出 / 草地 / 湖泊倒影 / 森林）",
        usage = "来源：Unsplash（https://unsplash.com）。许可：Unsplash License —— 可免费使用（含商用）、无需署名。" +
            "其余图形（启动图标 15 个 PNG + 3 个矢量 XML + 2 张调试用图）为本项目自绘/自产。",
        licenseName = "Unsplash License",
        url = "https://unsplash.com/license"
    ),
    LicenseItem(
        name = "Kotlin / kotlinx",
        version = "Kotlin 2.3.21（构建脚本声明）／实测解析 kotlin-stdlib 2.4.10（kotlinx-coroutines 同步解析）",
        usage = "用途：应用与构建脚本语言、协程运行时。",
        licenseName = "Apache License, Version 2.0",
        url = "https://github.com/JetBrains/kotlin"
    ),
    LicenseItem(
        name = "Compose Multiplatform（JetBrains Compose）",
        version = "org.jetbrains.compose 1.11.0（声明）／实测解析 1.12.0（-android 构件）",
        usage = "用途：本地 :backdrop 模块的 Compose 运行时（版本与上游 Backdrop 2.0.0 一致）。",
        licenseName = "Apache License, Version 2.0",
        url = "https://github.com/JetBrains/compose-multiplatform"
    ),
    LicenseItem(
        name = "Android Gradle Plugin（AGP）",
        version = "AGP 9.2.1",
        usage = "用途：构建工具链（编译 / 打包本应用）。",
        licenseName = "Apache License, Version 2.0",
        url = "https://www.apache.org/licenses/LICENSE-2.0"
    )
)

/** 节标题：与设置页 [SectionTitle] 逐字同一套样式（labelLarge + primary + 上下间距）。 */
@Composable
private fun LicSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

/**
 * 【「更多设置」底部·「关于」节】App 名 + 版本（BuildConfig.VERSION_NAME，动态取 ✗ 不写死）
 * + 一行可点击的「开源许可与致谢 ›」入口（与「图片编辑 ›」「组件演示 ›」同级的二级页入口形态）。
 */
@Composable
internal fun LicenseAboutSection(onOpenLicenses: () -> Unit) {
    LicSectionTitle("关于")
    Text(
        "${stringResource(R.string.app_name)} · 版本 ${BuildConfig.VERSION_NAME}",
        fontSize = 12.sp,
        color = LicBodyColor
    )
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .then(licenseRowBg())
            .clip(PanelChipShape)
            .clickable { onOpenLicenses() }
            .padding(vertical = 10.dp)
            .semantics { contentDescription = "开源许可与致谢" },
        contentAlignment = Alignment.Center
    ) {
        Text("开源许可与致谢 ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
    Text(
        "第三方组件 · 版权行 · 许可证 · 链接",
        fontSize = 11.sp,
        color = LicHintColor
    )
}

/** 二级页④「开源许可与致谢」的全部内容（由控制中心面板的同一个滚动列承载）。 */
@Composable
internal fun LicensesPage() {
    val context = LocalContext.current
    LicSectionTitle("开源许可与致谢")
    Text(
        "${stringResource(R.string.app_name)} · 版本 ${BuildConfig.VERSION_NAME}。" +
            "本应用包含下列第三方开源项目，特此致谢。每条列出【项目名 + 版权行 + 许可证名 + 链接】；" +
            "版权行与许可文本【逐字】取自上游 LICENSE 文件（核对来源见页末），" +
            "Apache-2.0 与 MIT 的完整全文随本页附后；平台/框架类只标注许可证名与链接，" +
            "完整许可证文本见链接。",
        fontSize = 11.sp,
        color = LicBodyColor
    )

    LicSectionTitle("玻璃与形状（直接使用）")
    GlassLicenseItems.forEach { LicenseCard(it) }

    LicSectionTitle("平台与框架（Apache-2.0）")
    PlatformLicenseItems.forEach { LicenseCard(it) }

    LicSectionTitle("许可证全文（逐字照录）")
    Text(
        "Apache License 2.0 · 全文（Kyant0/AndroidLiquidGlass 与 Kyant0/Shapes 的 LICENSE 正文；" +
            "两份文件除末段版权行年份 2025 / 2026 外与此全文逐字相同）",
        fontSize = 10.sp,
        color = LicHintColor
    )
    LicenseCodeBlock(LicenseTexts.APACHE_2_0_FULL)
    Text(
        "MIT License · 全文（QWEA0/Liquid-Glass-Android）",
        fontSize = 10.sp,
        color = LicHintColor
    )
    LicenseCodeBlock(LicenseTexts.QWEA_MIT)

    LicSectionTitle("逐条核对来源")
    Text(
        "① Kyant0/AndroidLiquidGlass → github.com/Kyant0/AndroidLiquidGlass 的 LICENSE" +
            "（本工程内同名副本：LICENSES/Backdrop-APACHE-2.0.txt，与上游逐字节相同）\n" +
            "② Kyant0/Shapes → github.com/Kyant0/Shapes 的 LICENSE（raw master 分支）\n" +
            "③ QWEA0/Liquid-Glass-Android → github.com/QWEA0/Liquid-Glass-Android 的 LICENSE（MIT）\n" +
            "④ 平台/框架 → 构建元数据（Gradle 模块 POM 的 licenses 段）；未复制文本，只标注许可证名与链接。\n" +
            "本页的版权行/许可文本由脚本从上述 LICENSE 原文逐字生成，并逐字节校验（改动前后 diff 为空）。",
        fontSize = 10.sp,
        color = LicBodyColor
    )
}

/** 一条来源卡片：项目名 / 版本 / 用途 / 版权行（逐字）/ 许可证名 / 链接行。 */
@Composable
private fun LicenseCard(item: LicenseItem) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(licenseRowBg())
            .clip(PanelChipShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(item.name, fontSize = 14.sp, color = LicTitleColor)
        Text(item.version, fontSize = 11.sp, color = LicHintColor)
        Text(item.usage, fontSize = 11.sp, color = LicBodyColor)
        if (item.copyright.isNotEmpty()) {
            // 版权行：等宽字体 + 强调色 —— 一眼可辨认"这是逐字抄来的那一行"
            Text(
                item.copyright,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = LicAccentColor
            )
        } else {
            Text(
                "版权：各上游项目所有者（未复制其文本，见下方链接）",
                fontSize = 11.sp,
                color = LicBodyColor
            )
        }
        Text("许可证：${item.licenseName}", fontSize = 12.sp, color = LicTitleColor)
        LicenseLinkRow(item)
    }
}

/** 链接行：点按 = 系统浏览器打开上游 LICENSE；长按 = 复制 URL（Toast + logcat 取证）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LicenseLinkRow(item: LicenseItem) {
    val context = LocalContext.current
    Text(
        text = item.url,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = LicAccentColor,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = true,
                onClickLabel = "打开上游链接",
                role = null,
                onLongClickLabel = "复制链接",
                onLongClick = { copyLicenseUrl(context, item.url) },
                onDoubleClick = null,
                hapticFeedbackEnabled = true,
                onClick = { openLicenseUrl(context, item.url) }
            )
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "上游链接：${item.name}" }
    )
    Text("点按打开链接 · 长按复制链接", fontSize = 9.sp, color = LicHintColor)
}

/** 逐字文本块（等宽、小字号；直接渲染 [LicenseTexts] 的原始字符串 ⇒ 换行/缩进与上游一致）。 */
@Composable
private fun LicenseCodeBlock(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(LicCodeBg, shape = PanelChipShape)
            .clip(PanelChipShape)
            .padding(8.dp)
    ) {
        Text(
            text,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = LicBodyColor
        )
    }
}

/**
 * 点按链接：走官方 `Intent(ACTION_VIEW)`（系统浏览器 / 任一能处理 http 的应用）。
 * 任何异常（无浏览器、包可见性限制）都只 Toast 降级 ✗ 不抛 ⇒ 控制中心不会因为点一下链接就崩。
 */
private fun openLicenseUrl(context: Context, url: String) {
    AppDebugLog.log("LIC", "点按打开链接 $url")
    Log.i("LGLicense", "open url=$url")
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        Toast.makeText(context, "本机没有可打开该链接的应用：$url", Toast.LENGTH_LONG).show()
    }
}

/** 长按链接：复制 URL 到剪贴板（Toast 反馈；Android 13+ 系统还会自带一次复制提示）。 */
private fun copyLicenseUrl(context: Context, url: String) {
    AppDebugLog.log("LIC", "长按复制链接 $url")
    Log.i("LGLicense", "copy url=$url")
    val copied = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) false else {
            cm.setPrimaryClip(ClipData.newPlainText("license url", url))
            true
        }
    } catch (t: Throwable) {
        false
    }
    Toast.makeText(
        context,
        if (copied) "已复制链接：$url" else "复制失败：$url",
        Toast.LENGTH_SHORT
    ).show()
}
