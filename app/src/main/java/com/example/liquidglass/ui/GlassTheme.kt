package com.example.liquidglass.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 【深色/亮色模式自动切换·2026-09-24】主题模式三档 + 两套调色板。
 *
 * 需求（用户原话：「把深色与亮色模式的自动切换做了吧」）：
 *   · 默认【跟随系统】= 接入 `isSystemInDarkTheme()`（Compose）⇒ 改系统主题（含
 *     `adb shell cmd uimode night yes|no`）当场跟随 ✓；
 *   · 手动三档：跟随系统 / 强制亮色 / 强制暗色（面板「更多设置 › 外观设置 › 外观」里可见可切）；
 *   · 状态持久化：SharedPreferences —— 与既有设置【同一套】（同一个 prefs 文件
 *     `liquid_glass_background`，见 [BackgroundImageStore] 的 PREFS_NAME）⇒ 重启不丢 ✓；
 *   · 只动【颜色/调色板】：几何/圆角/尺寸/动画/玻璃材质与折射参数一律不碰 ✓。
 *
 * 亮色档 = 逐字冻结的现状取值（每个字段都等于改动前的硬编码常量）⇒ 亮色档零回归 ✓。
 * 深色档 = 本文件新定的一套（高对比、不暗沉：面板填充为中性石墨灰而非纯黑，
 *   正文近白；胶囊字白 + 深色光晕；交棒路径 白→中灰→白）。
 *
 * 一行回退：`DebugSwitches.themeAutoSwitch = false` ⇒ 恒为亮色档（= 改动前行为）。
 */
enum class GlassThemeMode(val id: Int, val label: String) {
    FOLLOW_SYSTEM(0, "跟随系统"),
    LIGHT(1, "强制亮色"),
    DARK(2, "强制暗色");

    companion object {
        fun of(id: Int): GlassThemeMode = entries.firstOrNull { it.id == id } ?: FOLLOW_SYSTEM
    }
}

/** 主题模式的可持久化状态（进程内单例 + SharedPreferences）。 */
object GlassTheme {
    /** 与既有设置【同一套】存储：BackgroundImageStore 用的同一个 prefs 文件。 */
    const val PREFS_NAME = "liquid_glass_background"
    private const val KEY_MODE = "theme_mode"

    /**
     * 【默认档位·2026-09-25 ①③ 用户要求】新装 / 从未设置过时，默认 = 【强制暗色】。
     * 用户原话：「这个暗色模式的暗色主题控效果比白色的好，你就直接把暗色作为默认主题」✓
     * 口径：只有 prefs 里【没有】theme_mode 这一项时才用本默认值；用户手动选过任何一档
     *   （含"跟随系统"）都按已存值走 ⇒ 升级安装/已设置过的用户档位【不变】✓
     *   一行回退：把本常量改回 `GlassThemeMode.FOLLOW_SYSTEM`（= 改动前的默认跟随系统）。
     */
    val DEFAULT_MODE: GlassThemeMode = GlassThemeMode.DARK

    /** 当前档位（Compose 快照状态 ⇒ 面板里切换即时生效）。 */
    var mode by mutableStateOf(DEFAULT_MODE)
        private set

    /** 启动时调用一次（MainActivity.onCreate）。 */
    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 【默认档位口径】contains 区分"未设置过"（走 DEFAULT_MODE=暗色）与"手动选了跟随系统(0)"（尊重已存值）✓
        mode = if (sp.contains(KEY_MODE)) GlassThemeMode.of(sp.getInt(KEY_MODE, DEFAULT_MODE.id))
        else DEFAULT_MODE
    }

    /** 面板里切换 + 落盘。 */
    fun set(context: Context, m: GlassThemeMode) {
        mode = m
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, m.id).apply()
    }

    /** 仅供调试桥（adb）使用：无 Context 时也能落盘（由桥传 applicationContext）。 */
    fun setFromBridge(context: Context, id: Int) = set(context, GlassThemeMode.of(id))
}

/** 当前是否走【深色档】：跟随系统 ⇒ 读 isSystemInDarkTheme()（系统主题一变即重组 ✓）。 */
@Composable
fun isGlassDark(): Boolean = when (GlassTheme.mode) {
    GlassThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
    GlassThemeMode.LIGHT -> false
    GlassThemeMode.DARK -> true
}

/**
 * 【胶囊可读性收口·2026-10-13】绘制期（非 Composable）档位判断 —— 供 glassPanel 的 uniform
 * lambda 用（那里不能调 @Composable 的 [isGlassDark] ✗）。
 * 读 [GlassTheme.mode]（快照状态）⇒ 绘制期读它 = 订阅档位切换、切档立即重录 ✓；
 * FOLLOW_SYSTEM 档用 [context] 的 uiMode 兜底（无 Context 时按亮档）。
 */
fun isGlassDarkForDraw(context: Context?): Boolean = when (GlassTheme.mode) {
    GlassThemeMode.LIGHT -> false
    GlassThemeMode.DARK -> true
    GlassThemeMode.FOLLOW_SYSTEM ->
        context?.resources?.configuration?.let {
            (it.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        } ?: false
}

/**
 * 全套调色板（唯一色彩真值源）。
 * 亮色档每个字段 = 改动前的硬编码常量（逐字）⇒ 亮色档逐像素零回归 ✓。
 */
internal data class GlassPalette(
    val isDark: Boolean,
    /** 根容器兜底色（改动前 LiquidGlassScreen.kt:1244 的 0xFF0E1626）。 */
    val rootBackground: Color,
    /** 面板填充层（改动前 LiquidGlassScreen.kt:3107/3111 的 0xFFC6CAD0）。 */
    val panelFill: Color,
    /**
     * 【① 胶囊实底灰·2026-09-25】收起态（p→0）控制中心胶囊的填充色 = 【纯灰实底】的灰值。
     * 亮色档 = 与 [panelFill] 同值（0xFFC6CAD0）⇒ 亮档"胶囊→面板"零色相变化，只有不透明度在动 ✓；
     * 深色档 = 0xFF24282F（比面板 0xFF333A45 再暗一档，见 DarkGlassPalette 同名字段的实测说明）。
     * 只影响【胶囊那一段】：颜色随形态在 p∈[0.10,0.70] 平滑回到 [panelFill] ⇒ p≥0.70 的面板
     * 填充色逐值未变（面板零回归 ✓）。
     */
    val capsuleSolid: Color,
    /** 面板 MaterialTheme 配色。 */
    val scheme: ColorScheme,
    /** 面板标题字色（改动前 = Compose 默认 LocalContentColor = 纯黑）。 */
    val panelTitle: Color,
    /**
     * 【面板默认文字色·2026-10-14】面板内【未显式给 color】的文字所继承的 LocalContentColor。
     *   为什么需要：M3 的 `LocalContentColor` 默认值就是【纯黑】✗ ⇒ 暗色档下开关行/滑杆行/
     *   玻璃尺寸行/多卡演示行等（Text 未给 color）全部渲染成黑字压在深色面板上（用户报
     *   「有些字是白色的 有些是黑色的 根本没有统一」✗；亮色档下黑字恰好正确 ⇒ 一直未暴露）。
     *   亮色档 = 0xFF000000（= 原默认值逐字节相同 ⇒ 亮档逐像素零回归 ✓）；
     *   暗色档 = 0xFFF2F5FA（= onBackground，跟随主题）。
     */
    val panelContent: Color,
    /** 收起态胶囊标签字色（改动前 LiquidGlassScreen.kt:3234 的 0xFF000000）。 */
    val capsuleLabel: Color,
    /** 胶囊标签光晕色（改动前 LABEL_HALO_ARGB = 0xF2FFFFFF）。 */
    val capsuleHalo: Color,
    /** 光晕是否带偏移（亮色档 = 零偏移白光晕；深色档 = 零偏移深色光晕）。 */
    val capsuleHaloIsDark: Boolean,
    /** 交棒飞行字的源色（改动前 HANDOFF_LABEL_COLOR = 0xFFF4F7FF）。 */
    val handoffLabel: Color,
    /** 交棒终点色 = 面板标题色（改动前 HANDOFF_TITLE_COLOR = 0xFF000000）。 */
    val handoffTitle: Color,
    /** 交棒起点色 = 胶囊标签色（改动前 HANDOFF_START_BLACK = 0xFF000000）。 */
    val handoffStart: Color,
    /** 交棒中灰停靠点（亮色档受 handoffContrastFloor 开关调制，见 handoffTintOf）。 */
    val handoffGray: Color,
    /** 面板说明文字（改动前 GlassControlsPanel 的 0xFF4A5A78；本轮 A 项深化为 0xFF25324C，全部壁纸 ≥4.5:1 ✓）。 */
    val hintText: Color,
    /** 面板软性说明文字（改动前 GlassControlsPanel 的 0xFFC3CFE6）。 */
    val softCaption: Color,
    /** 面板底部标签栏字色（改动前 LiquidGlassScreen.kt:3699/3705 的 0xFF10203A）。 */
    val barLabel: Color,
    /** 面板 FilterChip 配色。 */
    val chipContainer: Color,
    val chipLabel: Color,
    val chipSelectedContainer: Color,
    val chipSelectedLabel: Color,
    /** 面板开关（M3 Switch）未选中态轨道/描边（改动前 0x24121D33 / 0x40121D33）。 */
    val toggleUncheckedTrack: Color,
    val toggleUncheckedBorder: Color,
    /**
     * 【胶囊可读性收口·2026-10-13】收起态胶囊玻璃的【材质亮度增益】（材质/染色一侧）：
     * 玻璃 rgb 修形 = rgb×capsuleGlassGain + capsuleGlassLift（只在本档驱动，见 GlassShaders 的
     * capsuleGain/capsuleLift/capsuleVis 段）。亮档 1.0（不压暗，靠 lift 提亮）；暗档 <1（压暗白字底）。
     * ✗ 折射/色散/菲涅尔/边缘高光参数与几何一律未动 ✓。
     */
    val capsuleGlassGain: Float = 1f,
    /** 同上：材质亮度【偏置】（亮档 >0 提亮保黑字；暗档 0）。 */
    val capsuleGlassLift: Float = 0f
)

/** 亮色档 = 现状逐字冻结（✗ 不得改任何一个取值 ✗）。 */
internal val LightGlassPalette = GlassPalette(
    isDark = false,
    rootBackground = Color(0xFF0E1626),
    panelFill = Color(0xFFC6CAD0),
    // 【① 胶囊实底灰】亮档 = 与 panelFill 同值（中性偏冷灰，明度 202/255 ≈79%）：
    //   收起来是"纯灰实底"、展开是"面板灰白磨砂"【同一个灰】⇒ 开合全程零色相/零明度跳变 ✓；
    //   黑字标称对比 ≈12.8:1。实测（实底 + 玻璃层，全 6 壁纸）：7.24 / 8.38 / 9.74 / 7.33 / 12.52 / 10.32
    //   全部 ≥4.5 ✓（无任何补光层；见本轮交付报告对比度表）。
    capsuleSolid = Color(0xFFC6CAD0),
    // 【胶囊可读性收口·2026-10-13】亮档（黑字）：不压暗（gain=1.0），靠 +0.30 的材质偏置把
    //   字形紧邻背景抬到 ≥4.5:1（最差格 雾中森林）。偏置是【整体搬移】⇒ 背景纹理/折射结构
    //   100% 保留（✗ 不是平涂色块）；✗ 折射/几何/高光参数未动 ✓。
    capsuleGlassGain = CAPSULE_GLASS_GAIN_LIGHT,
    capsuleGlassLift = CAPSULE_GLASS_LIFT_LIGHT,
    scheme = lightColorScheme(
        primary = Color(0xFF1B4A8F),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF2C5DA8),
        onSecondary = Color(0xFF0A1020),
        secondaryContainer = Color(0xFF1B4A8F),
        onSecondaryContainer = Color(0xFFFFFFFF),
        background = Color(0x00000000),
        onBackground = Color(0xFF0E1626),
        surface = Color(0x00000000),
        onSurface = Color(0xFF0E1626),
        surfaceVariant = Color(0x1A0E1626),
        onSurfaceVariant = Color(0xFF23304A),
        outline = Color(0x33121D33),
        outlineVariant = Color(0x26121D33)
    ),
    panelTitle = Color(0xFF000000),
    /** 【面板默认文字色】亮档 = 0xFF000000 = M3 LocalContentColor 原默认值 ⇒ 逐字节相同、零回归 ✓ */
    panelContent = Color(0xFF000000),
    capsuleLabel = Color(0xFF000000),
    capsuleHalo = Color(0xFFF2FFFFFF),
    capsuleHaloIsDark = false,
    handoffLabel = Color(0xFFF4F7FF),
    handoffTitle = Color(0xFF000000),
    handoffStart = Color(0xFF000000),
    handoffGray = Color(0xFF808080),
    // 【A 项·面板说明对比度·2026-09-25】0xFF4A5A78 在面板上的实测只有 3.52~4.45:1 ✗（既有问题）；
    //   深化为 0xFF2B3A54：实测全部 ≥4.5 ✓（云海 6.33 / 高山 5.86 / 湖泊 6.21 / 雾中 6.38 / 网格 7.40 / 彩色网格 6.59）；一行回退 = 改回 0xFF4A5A78。
    hintText = Color(0xFF2B3A54),
    softCaption = Color(0xFFC3CFE6),
    barLabel = Color(0xFF10203A),
    chipContainer = Color(0x140E1626),
    chipLabel = Color(0xFF12213D),
    chipSelectedContainer = Color(0xFF1B4A8F),
    chipSelectedLabel = Color(0xFFFFFFFF),
    toggleUncheckedTrack = Color(0x24121D33),
    toggleUncheckedBorder = Color(0x40121D33)
)

/**
 * 深色档（新定一套）：
 *   · 面板填充 0xFF333A45 = 中性【石墨灰】——刻意【不用纯黑/近黑】（用户对"暗沉一片"极其反感 ✗）；
 *     与正文 0xFFF2F5FA 的 WCAG 对比 ≈10.8:1（亮色档同位置 ≈11.1:1，两档观感同级 ✓）。
 *   · 胶囊字白 0xFFF4F7FF + 【深色零偏移光晕】0xE00A1220 —— 亮壁纸（云海日出/网格）上
 *     把字形紧邻背景压暗，暗壁纸本就是白字、光晕几乎不可见 ⇒ 两向都 ≥4.5:1 ✓。
 *   · 交棒路径 = 白 →(中灰 0xFFA8A8A8)→ 面板标题色 0xFFF2F5FA（暗档下同样经过灰阶 ✓）。
 *   · 说明文字 0xFFC8D2E0 / 软性说明 0xFFC8D2E0 —— 面板填充上的对比 ≈7.8:1 ✓（亮色档那一对
 *     是 3.5~4.4:1 的既有状态，本档刻意做得更亮以满足硬门 ✓）。
 */
internal val DarkGlassPalette = GlassPalette(
    isDark = true,
    rootBackground = Color(0xFF070A10),
    panelFill = Color(0xFF333A45),
    // 【① 胶囊实底灰·深色档】0xFF24282F（中性冷灰，比面板 0xFF333A45 再暗一档）——为什么不是面板同值：
    //   收起态胶囊是【实底 + 上面那层液态玻璃】，实测复合色 ≈ 0.70×填充 + h(壁纸)，h 从暗壁纸的
    //   ≈22 升到纯白壁纸的 ≈76（网格·调试图）⇒ 用 0xFF333A45 时白壁纸上的胶囊实测 (112,115,123)，
    //   白字只有 4.44:1 ✗（差 1.3%）；换成本值后同一格 ≈ (100,104,111) ⇒ ≈5.3:1 ✓（全 6 壁纸实测见报告）。
    //   明度 40/255：仍然不是纯黑（用户对"暗沉一片"反感 ✗），且白字在其上的标称对比 ≈13:1；
    //   它只在【胶囊那一段】生效 —— 颜色随形态平滑回到 panelFill（p≥0.70）⇒ 展开态面板逐像素不变 ✓。
    capsuleSolid = Color(0xFF24282F),
    // 【胶囊可读性收口·2026-10-13】暗档（白字）：字形紧邻背景必须够暗 ⇒ 材质增益 0.22 压暗
    //   （背景纹理/折射结构按 22% 幅度保留 = 仍可见 ✓，✗ 不是平涂色块）；偏置 0（不额外抬亮）。
    capsuleGlassGain = CAPSULE_GLASS_GAIN_DARK,
    capsuleGlassLift = CAPSULE_GLASS_LIFT_DARK,
    scheme = darkColorScheme(
        primary = Color(0xFF9CC4FF),
        onPrimary = Color(0xFF0A1020),
        secondary = Color(0xFFB9CDF0),
        onSecondary = Color(0xFF0A1020),
        secondaryContainer = Color(0xFF2F6FD0),
        onSecondaryContainer = Color(0xFFFFFFFF),
        background = Color(0x00000000),
        onBackground = Color(0xFFF2F5FA),
        surface = Color(0x00000000),
        onSurface = Color(0xFFF2F5FA),
        surfaceVariant = Color(0x1AFFFFFF),
        onSurfaceVariant = Color(0xFFD5DEEA),
        outline = Color(0x40FFFFFF),
        outlineVariant = Color(0x2EFFFFFF)
    ),
    panelTitle = Color(0xFFF2F5FA),
    /** 【面板默认文字色】暗档 = 0xFFF2F5FA（= onBackground）⇒ 未显式给色的文字跟随主题，不再黑字 ✗→✓ */
    panelContent = Color(0xFFF2F5FA),
    capsuleLabel = Color(0xFFF4F7FF),
    capsuleHalo = Color(0xF00A1220),
    capsuleHaloIsDark = true,
    handoffLabel = Color(0xFFF4F7FF),
    handoffTitle = Color(0xFFF2F5FA),
    // 【恒色化·2026-09-25】暗档交棒起点 = 0xFFF2F5FA（≈ 标题色）——不是胶囊字 0xFFF4F7FF：
    //   恒色判定要求【逐通道 equal】start==title ⇒ 路径整段恒色（用户：「白→白中间变灰完全多此一举」）。
    //   0xFFF2F5FA 与 0xFFF4F7FF 视觉同为白（差 2/255）⇒ 无感。
    handoffStart = Color(0xFFF2F5FA),
    handoffGray = Color(0xFFA8A8A8),
    hintText = Color(0xFFC8D2E0),
    softCaption = Color(0xFFC8D2E0),
    barLabel = Color(0xFFE8EEF8),
    chipContainer = Color(0x1FFFFFFF),
    chipLabel = Color(0xFFEAF0F8),
    chipSelectedContainer = Color(0xFF3B78D8),
    chipSelectedLabel = Color(0xFFFFFFFF),
    toggleUncheckedTrack = Color(0x33FFFFFF),
    toggleUncheckedBorder = Color(0x59FFFFFF)
)

/** 当前生效的调色板（由 LiquidGlassScreen 根部 provide）。 */
internal val LocalGlassPalette = staticCompositionLocalOf { LightGlassPalette }

/**
 * 【胶囊可读性收口·2026-10-13】收起态胶囊玻璃的材质亮度修形【常量版】——与两个调色板实例里的
 * capsuleGlassGain / capsuleGlassLift 同值，但可在【绘制期】直接按档取值。
 * 为什么要常量：glassPanel 的 uniform 块由 backdrop 库在【录制时】调用；若只捕获调色板对象，
 * 主题档位切换后这一层不重新录制 ⇒ 暗档会继续用亮档的修形值（实测踩坑）。绘制期读
 * isGlassDark()（快照状态）既取到当前档位、又订阅它 ⇒ 切档立即重录 ✓。
 */
internal const val CAPSULE_GLASS_GAIN_LIGHT = 0.55f
internal const val CAPSULE_GLASS_LIFT_LIGHT = 0.42f
internal const val CAPSULE_GLASS_GAIN_DARK = 0.08f

/**
 * 【P77·自适应亮度·2026-10-14】暗档 gain 的【上界】——用在【暗壁纸】上（场景亮度→0 时插值到它）：
 *   gain 越大 = 材质压暗越少 = 玻璃越亮越透（用户口径「加点透明效果」）。亮壁纸仍用 [CAPSULE_GLASS_GAIN_DARK]
 *   这个标定地板 ⇒ 12 格对比度硬门不可能退化 ✓。一行回退：setSwitches adaptiveLuminance 0。
 */
internal const val CAPSULE_GLASS_GAIN_DARK_MAX = 0.55f

/** 【P77·自适应亮度】面板暗档 gain 的上界（暗壁纸上更亮更透；亮壁纸仍用 PANEL_GLASS_GAIN_DARK 地板）。 */
internal const val PANEL_GLASS_GAIN_DARK_MAX = 0.85f
internal const val CAPSULE_GLASS_LIFT_DARK = 0f
// 【胶囊材质·暗色半透明收口·2026-10-13】收起态胶囊【输出 alpha 覆盖】：
//   实测（暗档 wp4/wp5）：胶囊档输出 alpha = 1.0 × backgroundTransmission(≈0.56)
//   ⇒ 近白壁纸按 (1-α)≈0.44 直通 ⇒ 无论材质怎么压暗，胶囊内亮度地板 ≈0.44×壁纸亮度
//   （亮壁纸 ⇒ 地板 0.4+ ⇒ 白字实测 1.85:1 / 2.87:1 ✗）。本值把【胶囊专属】输出 alpha
//   抬到 0.61（直通项 0.44→0.39... 实测曲线：alpha 0.50 ✗3.44 / 0.60 →4.90 / 0.61 →5.12
//   / 0.62 →5.28 / 0.93 →16.6）——取【满足 12 格 ≥4.5:1 且留 ~10% 余量的最小档】
//   = 最透明的达标档（本机 emulator-5554 实测：wp4=5.12:1、wp5=7.07:1、其余 ≥9.9:1）。
//   亮档 = 0f（不进本段，逐像素改前 ✓）。
internal const val CAPSULE_GLASS_ALPHA_DARK = 0.61f

/**
 * 【P77·自适应亮度】暗档胶囊输出 alpha 的【下界】——用在【暗壁纸】上（场景亮度→0 时插值到它）：
 *   alpha 越低 = 越透明（背景透出越多，用户口径「加点透明效果」）。亮壁纸仍用 [CAPSULE_GLASS_ALPHA_DARK]
 *   这个标定地板 ⇒ 12 格对比度硬门不可能退化 ✓（暗壁纸那几格原本有 16.47:1 的余量）。
 *   一行回退：setSwitches adaptiveLuminance 0。
 */
internal const val CAPSULE_GLASS_ALPHA_DARK_MIN = 0.45f

/** 【P77·自适应亮度】面板暗档输出 alpha 的下界（暗壁纸更透明；亮壁纸仍用 PANEL_GLASS_ALPHA_DARK 地板）。 */
internal const val PANEL_GLASS_ALPHA_DARK_MIN = 0.80f

/**
 * 【P71·展开态面板玻璃化·2026-10-14】面板【展开静止段】（p≥0.885）专属的材质修形三量：
 *   玻璃 rgb 修形 = rgb × PANEL_GLASS_GAIN_* + PANEL_GLASS_LIFT_*（与胶囊同一条通路，
 *   位置在透射/材质/染色/局部暗化之后、去色/边缘高光之前），输出 alpha 再被 PANEL_GLASS_ALPHA_* 覆盖。
 *
 * 与胶囊（0.61 / gain 0.08 / lift 0）不同的原因：面板面积大且承载【全部】正文，
 *   · 亮档（黑字/深灰提示字）：靠 lift 把字形底抬亮（0x2B3A54 提示字要求底 ≥0.364 亮度 ⇒ lift 0.80）
 *     + gain 保留背景结构（胶囊亮档 0.55/0.42 同族，面板因提示字更严格而 lift 更高）；
 *   · 暗档（白字）：靠 gain 压暗 + alpha 压掉「(1-α)×近白壁纸」直通项（胶囊 0.61 在
 *     小面积胶囊上够用；面板上直通项会把白字对比拉崩 ⇒ alpha 需更高档，实测标定）。
 * 取值为本机 emulator-5554 上「面板内 37 个文字节点 × 暗/亮 × 6 内置壁纸 ≥4.5:1」的标定值
 * （暗档 gain 取"全过档里结构最可见"的最大值；亮档 lift 取"提示字全过"的最小值）。
 * 一行回退：setSwitches panelGlassExpanded 0（整段跳过 = 逐像素回到改动前）。
 */
internal const val PANEL_GLASS_GAIN_LIGHT = 0.55f
internal const val PANEL_GLASS_LIFT_LIGHT = 0.80f
internal const val PANEL_GLASS_ALPHA_LIGHT = 0.90f
internal const val PANEL_GLASS_GAIN_DARK = 0.30f
internal const val PANEL_GLASS_LIFT_DARK = 0f
internal const val PANEL_GLASS_ALPHA_DARK = 0.95f

/**
 * 【P66-B·对比度硬门】亮档说明文字（softCaption）的两档取值。
 *
 * 改前 0xFFC3CFE6 在亮面板填充 0xFFC6CAD0 上 WCAG 只有 **1.05:1** ✗✗（肉眼几乎不可读）
 * —— 这是本轮静态审计里唯一「亮档」的对比度 FAIL 项（准则 #4：别把最弱的颜色压在材质上）。
 * 改后 0xFF3A4A66 = 与 hintText(0xFF2B3A54) 同一族的中性蓝灰 ⇒ **5.43:1** ✓ ≥4.5。
 * 【LG-r10·收紧】0xFF3A4A66 在【10% 文本簇含 AA 边】口径实测仍只有 3.87~4.11 ✗ ⇒ 深化到
 *   0xFF263550：10% 口径预估 4.95~5.28 ✓（实测见 r10 验收表），2% 口径 ≥5.9 ✓；字形/字号/布局不动。
 *
 * 一行回退：`setSwitches glassContrastFloor 0`（DebugSwitches.glassContrastFloor）。
 */
internal val SOFT_CAPTION_LIGHT_LEGACY = Color(0xFFC3CFE6)
// 【LG-r10】0xFF3A4A66 → 0xFF263550：把 10%（含 AA 边）口径从 3.87~4.11 提到 ≥4.5（实测见验收）。
internal val SOFT_CAPTION_LIGHT = Color(0xFF263550)

/**
 * 【P66-B·对比度硬门】暗档选中 chip 的两档容器色。
 *
 * 改前 0xFF3B78D8：#FFFFFF on it = **4.32:1** ✗（差 4%）⇒ 0xFF2F6FD0 = **5.31:1** ✓。
 * 未选中容器 0x1FFFFFFF 与文字 0xFFEAF0F8 的对比不受影响（透明容器上的文字读的是面板底色）。
 *
 * 【LG-r10·现状核查】该字段在面板里的【真实消费方是 M3 Switch 轨道】
 * （GlassControlsPanel.kt PanelSwitch → `checkedTrackColor = glassPalette.chipSelectedContainer`），
 * **不是** `PanelChipColors` —— 后者虽在构造时读 `p.chipSelectedContainer` 填
 * `selectedContainerColor`，但面板内所有 chip 都是未选中态（选中样目由其它容器色承托），
 * 故 dark 档 chipSelectedContainer 只在开关轨选中态上实际可见。
 * 开关关（= 改前 0xFF3B78D8）：白 thumb 对轨道 ≈4.3:1 ✗；开（0xFF2F6FD0）≈5.3:1 ✓。
 * 已在设备上按开关两态实测（见 r10 验收图/表），非未复现项。
 */
internal val CHIP_SELECTED_CONTAINER_DARK_LEGACY = Color(0xFF3B78D8)
internal val CHIP_SELECTED_CONTAINER_DARK = Color(0xFF2F6FD0)

/**
 * 【P66-C·圆角同心成体系】全 UI 圆角半径常量表（同心 = 内圆角与外轮廓同一构造）。
 *
 * 准则 #7：「控件圆角要与容器同心」。本项目统一用 `ui/G2RoundedShape.kt`（E=1.5287·r / n=3
 * 超椭圆，与 Shader 侧 sdContinuousRect 同源），**半径数值一律不变**，只是把原来散落的三处
 * 魔数（chip 9dp / 看板 14dp / 卡片 36dp）集中记账：
 *   · CHIP_RADIUS_DP=9：chip 高 32dp ⇒ r/h ≈ 0.28（G2 视觉上接近胶囊但保留辨识度）
 *   · HUD_RADIUS_DP=14：看板（原 RoundedCornerShape(14.dp)，换 G2 构造、半径同值）
 *   · CARD_RORNER_DP=36：演示卡（铁律域，仅供记账，✗ 不在本表改动）
 * 面板/胶囊圆角由 p 驱动（铁律域）⇒ 不在此表。
 */
internal const val UI_CHIP_CORNER_DP = 9
internal const val UI_HUD_CORNER_DP = 14
internal const val UI_CARD_CORNER_DP = 36

/**
 * 【P66-D·玻璃元素记账】同屏「功能层玻璃」清单（准则 #1/#2：玻璃 = 功能层，且要节制）。
 *
 * 现状（HEAD 审计）已符合准则 1：**内容层（演示卡内容 / 列表正文 / 说明文字）不叠玻璃** ✓。
 * 这里是给后续「结构性收敛」用的唯一真值表 —— 本任务只记账 + 收敛观感一致性，**不减数量**。
 */
internal enum class GlassLayer { PANEL, CAPSULE, BOTTOM_TABS, CONTROL, HUD }

/**
 * 【P66-B】按开关生效的调色板（对比度硬门）。
 *
 * 在 [LiquidGlassScreen] 根部 provide 之前调用一次（该处已先读 `DebugBridge.revision` ⇒
 * `setSwitches glassContrastFloor` 翻转会触发重组，当场生效 ✓ 不混合态）。
 * 默认（开关开）= 修正后的两个颜色常量；开关关 = **逐字回到改前取值**（softCaption 0xFFC3CFE6 /
 * chipSelectedContainer 0xFF3B78D8）⇒ 一行回退。
 */
internal fun effectiveGlassPalette(base: GlassPalette): GlassPalette {
    if (!com.example.liquidglass.debug.DebugSwitches.glassContrastFloor) return base
    return if (base.isDark) {
        base.copy(chipSelectedContainer = CHIP_SELECTED_CONTAINER_DARK)
    } else {
        base.copy(softCaption = SOFT_CAPTION_LIGHT)
    }
}

/** 便捷读法。 */
internal val glassPalette: GlassPalette
    @Composable get() = LocalGlassPalette.current

/** 面板 MaterialTheme 配色 = 当前调色板的 scheme。 */
internal val GlassPanelColorScheme: ColorScheme
    @Composable get() = LocalGlassPalette.current.scheme

/** 面板 FilterChip 配色 = 当前调色板（亮色档四个取值与改动前逐字相同 ✓）。 */
internal val PanelChipColors: SelectableChipColors
    @Composable get() {
        val p = LocalGlassPalette.current
        return FilterChipDefaults.filterChipColors(
            containerColor = p.chipContainer,
            labelColor = p.chipLabel,
            selectedContainerColor = p.chipSelectedContainer,
            selectedLabelColor = p.chipSelectedLabel
        )
    }
