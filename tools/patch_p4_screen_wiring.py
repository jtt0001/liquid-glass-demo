# -*- coding: utf-8 -*-
"""P4 接线：LiquidGlassScreen 接钻石页面 + 分支专用包名/版本号。

纪律：整行文本锚点 + 计数断言 + 每处立即回读。
"""
import io

BASE = "/Users/jt/Projects/lg-diamond/"
SCREEN = "app/src/main/java/com/example/liquidglass/ui/LiquidGlassScreen.kt"
GRADLE = "app/build.gradle.kts"


def read(rel):
    return io.open(BASE + rel, encoding="utf-8").read()


def write(rel, s):
    io.open(BASE + rel, "w", encoding="utf-8").write(s)


def replace_once(rel, old, new, tag):
    s = read(rel)
    n = s.count(old)
    assert n == 1, "[%s] 锚点计数=%d (期望 1): %r" % (tag, n, old[:90])
    s = s.replace(old, new, 1)
    write(rel, s)
    assert new in read(rel), "[%s] 回读失败" % tag
    print("OK  %s" % tag)


# ---------------- 1) 分支专用包名 / 版本号 ----------------
# 为何改包名：主树（另一些会话）正在同一个 emulator-5554 上反复安装 com.liqglass.ultraclear
# （实测它们 18:21:31 刚装了 versionCode=286 / versionName=2.40.0-glassstyle）。
# 同包名 = 两边互相覆盖、打断对方的抓图/帧数据；用户已明确允许「分支上单独改 applicationId」。
# ⇒ 本分支用独立包名 com.liqglass.diamond：两版 APK 同机共存、互不干扰（功能集完全一致）。
s = read(GRADLE)
old = '        applicationId = "com.liqglass.ultraclear"'
assert s.count(old) == 1, "applicationId 锚点计数 != 1"
s = s.replace(old, '        // 【分支 feat/diamond-demo 专用】独立包名：与主分支 com.liqglass.ultraclear 同机共存、互不覆盖\n        applicationId = "com.liqglass.diamond"', 1)
s = s.replace("        versionCode = 286", "        versionCode = 300", 1)
s = s.replace('        versionName = "2.40.0-diamond"', '        versionName = "3.0.0-diamond"', 1)
write(GRADLE, s)
s = read(GRADLE)
for kw in ['applicationId = "com.liqglass.diamond"', "versionCode = 300", 'versionName = "3.0.0-diamond"']:
    assert s.count(kw) == 1, "gradle 回读失败: %r" % kw
print("OK  gradle_pkg_version  包名=com.liqglass.diamond versionCode=300 versionName=3.0.0-diamond")

# ---------------- 2) 屏幕层：状态 + 调试桥 + 入口参数 + 页面渲染 ----------------
# 2a) 状态
replace_once(
    SCREEN,
    "        val uiState = remember { GlassUiState() }",
    "        val uiState = remember { GlassUiState() }\n"
    "        // 【3D 钻石演示】状态（可见性/姿态/惯性/光学参数）；页面由 root Box 末端根据 visible 组合。\n"
    "        val diamondState = com.example.liquidglass.diamond.rememberDiamondDemoState()",
    "screen_state",
)

# 2b) 调试桥注册（与 panel/cards/advanced 同一处 DisposableEffect）
replace_once(
    SCREEN,
    "                DebugBridge.panel = panelBridge",
    "                // 【3D 钻石演示】调试桥（cmd=diamond：open/close/set/spin/params/dump）\n"
    "                val diamondBridge = com.example.liquidglass.diamond.DiamondDemoBridge(diamondState)\n"
    "                DebugBridge.panel = panelBridge",
    "screen_bridge_new",
)
replace_once(
    SCREEN,
    "                DebugBridge.cards = cardsBridge\n                onDispose {",
    "                DebugBridge.cards = cardsBridge\n"
    "                DebugBridge.diamond = diamondBridge\n"
    "                onDispose {",
    "screen_bridge_assign",
)
replace_once(
    SCREEN,
    "                    if (DebugBridge.cards === cardsBridge) DebugBridge.cards = null",
    "                    if (DebugBridge.cards === cardsBridge) DebugBridge.cards = null\n"
    "                    if (DebugBridge.diamond === diamondBridge) DebugBridge.diamond = null",
    "screen_bridge_dispose",
)

# 2c) 一级菜单「钻石」入口的回调
replace_once(
    SCREEN,
    "                                onOpenAdvanced = { panelAdvanced = true },",
    "                                onOpenAdvanced = { panelAdvanced = true },\n"
    "                                // 【3D 钻石演示】一级菜单「钻石」入口 → 打开演示页（页面自己画在 root Box 末端、\n"
    "                                // 盖住面板与卡片；backdrop 用页面背景捕获层 ⇒ 钻石从壁纸采样折射）\n"
    "                                onOpenDiamond = { diamondState.visible = true },",
    "screen_entry_cb",
)

# 2d) 页面渲染（root Box 最后一个子项 ⇒ 画在包括「控制中心」交棒文字在内的一切之上）
HANDOFF_TAIL = """                                this@drawWithContent.drawContent()
                                canvas.restore()
                            }
                        }
                )"""
OVERLAY = """

                // ===== 【6. 3D 钻石演示页】=====
                // 由一级菜单「钻石」入口打开（state.visible）；backdrop = 页面背景捕获层（壁纸），
                // 钻石对其做物理正确实体折射（入面 Snell → 内部 TIR → 出射再折射 → 采样背景）。
                // 位置：root Box 的【最后一个子项】⇒ 盖住卡片/面板/交棒文字；不开时不组合、零成本。
                // ✗ 不给它加 graphicsLayer 旋转/缩放（本项目踩过“采样坐标对不上”的坑）。
                if (diamondState.visible) {
                    com.example.liquidglass.diamond.DiamondDemoPage(
                        state = diamondState,
                        backdrop = adapter.captureLayer,
                        sizeFraction = uiState.glassSize,
                        onBack = { diamondState.visible = false },
                        modifier = Modifier.fillMaxSize()
                    )
                }"""
replace_once(SCREEN, HANDOFF_TAIL, HANDOFF_TAIL + OVERLAY, "screen_overlay")

# ---------------- 3) 总回读 ----------------
s = read(SCREEN)
for kw, expect in [
    ("val diamondState = com.example.liquidglass.diamond.rememberDiamondDemoState()", 1),
    ("val diamondBridge = com.example.liquidglass.diamond.DiamondDemoBridge(diamondState)", 1),
    ("DebugBridge.diamond = diamondBridge", 1),
    ("if (DebugBridge.diamond === diamondBridge) DebugBridge.diamond = null", 1),
    ("onOpenDiamond = { diamondState.visible = true },", 1),
    ("com.example.liquidglass.diamond.DiamondDemoPage(", 1),
]:
    c = s.count(kw)
    assert c == expect, "[final] %r 出现 %d 次（期望 %d）" % (kw, c, expect)
print("OK  screen_final_check  6 项关键字就位")

# 括号平衡（粗量，与 C 代理同口径）：改后应与改前差值 = 新增块里的 {} 数
print("OK  brace  { = %d , } = %d" % (s.count("{"), s.count("}")))
print("ALL WIRING APPLIED")
