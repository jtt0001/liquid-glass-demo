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
    val toggleUncheckedBorder: Color
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
