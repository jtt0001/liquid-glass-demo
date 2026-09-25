#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P0 接线补丁：strings(app_name=钻石) / build.gradle(版本号) / DebugBridge(cmd=diamond 桥)

纪律：每处补丁用【整行文本锚点】；锚点计数断言；每处改完【立即回读】断言；任一失败 exit 1。
"""
import io, re

BASE = "/Users/jt/Projects/lg-diamond/"
DB = "app/src/main/java/com/example/liquidglass/debug/DebugBridge.kt"
STRINGS = "app/src/main/res/values/strings.xml"
GRADLE = "app/build.gradle.kts"


def read(rel):
    return io.open(BASE + rel, encoding="utf-8").read()


def write(rel, s):
    io.open(BASE + rel, "w", encoding="utf-8").write(s)


def replace_once(rel, old, new, tag):
    s = read(rel)
    n = s.count(old)
    assert n == 1, "[%s] 锚点计数=%d (期望 1): %r" % (tag, n, old[:80])
    s = s.replace(old, new, 1)
    write(rel, s)
    s2 = read(rel)
    assert new in s2, "[%s] 回读失败" % tag
    print("OK  %s" % tag)


def insert_after(rel, anchor, block, tag):
    replace_once(rel, anchor, anchor + "\n" + block, tag)


# ---------------- 1) strings.xml: app_name -> 钻石 ----------------
replace_once(
    STRINGS,
    '<string name="app_name">Liquid Glass</string>',
    '<string name="app_name">钻石</string>',
    "strings_app_name",
)

# ---------------- 2) build.gradle.kts: 版本号递增（整行匹配；不用只认数字的正则） ----------------
s = read(GRADLE)
old_vc = "        versionCode = 285"
assert s.count(old_vc) == 1, "versionCode 锚点计数 != 1"
s = s.replace(old_vc, "        versionCode = 286", 1)
write(GRADLE, s)
s = read(GRADLE)
m = re.search(r'versionCode\s*=\s*(\d+)', s)
assert m and m.group(1) == "286", "versionCode 回读失败: %r" % (m.group(0) if m else None)
m2 = re.search(r'versionName\s*=\s*"([^"]*)"', s)
assert m2 and m2.group(1) == "2.39.0-integrate", "versionName 前置回读失败: %r" % (m2.group(0) if m2 else None)
s = s.replace('        versionName = "2.39.0-integrate"', '        versionName = "2.40.0-diamond"', 1)
write(GRADLE, s)
s = read(GRADLE)
m2 = re.search(r'versionName\s*=\s*"([^"]*)"', s)
assert m2 and m2.group(1) == "2.40.0-diamond", "versionName 回读失败: %r" % (m2.group(0) if m2 else None)
print("OK  gradle_version  versionCode=286 versionName=%s" % m2.group(1))

# ---------------- 3) DebugBridge.kt ----------------
insert_after(
    DB,
    "import com.example.liquidglass.glass.GlassDebugMode",
    "import com.example.liquidglass.diamond.DiamondBridgeApi",
    "db_import",
)

insert_after(
    DB,
    "    var cards: Cards? = null",
    """
    /**
     * 【3D 钻石演示】调试桥：由 ui/LiquidGlassScreen.kt 在组合期注册（cmd=diamond）。
     * null = 演示模块未接线（命令返回 FAILED 一行，不影响任何既有命令）。
     */
    @Volatile
    var diamond: DiamondBridgeApi? = null""",
    "db_handle",
)

insert_after(
    DB,
    '    private const val CMD_FRAME_TIMELINE = "frameTimeline"',
    '    private const val CMD_DIAMOND = "diamond"',
    "db_cmd_const",
)

replace_once(
    DB,
    "        CMD_DUMP_STATE, CMD_DUMP_FRAME_STATS, CMD_FRAME_TIMELINE\n    )",
    "        CMD_DUMP_STATE, CMD_DUMP_FRAME_STATS, CMD_FRAME_TIMELINE, CMD_DIAMOND\n    )",
    "db_all_commands",
)

insert_after(
    DB,
    "                CMD_FRAME_TIMELINE -> frameTimeline(context, intent)",
    "                CMD_DIAMOND -> diamond(intent)",
    "db_dispatch",
)

insert_after(
    DB,
    " *                                                       先 setSwitches 打开；采集途中置 false 会自动停）。",
    """ *   diamond          --es action <open|close|set|spin|params|dump>  [--ef yaw/--ef pitch/--ef speed/
 *                                                                 [--ei bounces/--ef dispersion/--ef ior/
 *                                                                  --es cut/--ei debug]
 *                                                       【3D 钻石演示】打开/关闭演示页 · 定格姿态 · 设转速 ·
 *                                                       改光学参数 · 状态一行（桥 = DebugBridge.diamond）""",
    "db_doc",
)

HANDLER = '''    // ---------------- 1b) diamond（【3D 钻石演示】桥；纯新增，不改变任何既有命令行为） ----------------

    /**
     * 钻石演示命令：`--es cmd diamond --es action <open|close|set|spin|params|dump>`
     *   open / close                                打开 / 关闭演示页
     *   set    --ef yaw <deg> --ef pitch <deg>      定格姿态（度）
     *   spin   --ef speed <degPerSec>               转速（0 = 停）
     *   params [--ei bounces 0..4] [--ef dispersion <x>] [--ef ior <x>] [--es cut <NAME>] [--ei debug 0..4]
     *   dump                                        状态一行
     * 数值入口一律先挡 NaN/Inf（Float.coerceIn 对 NaN 放行，本项目已踩过：写 NaN 会把几何报废）。
     */
    private fun diamond(intent: Intent): List<String> {
        val bridge = diamond
            ?: return listOf("diamond FAILED：演示桥未注册（应用 UI 未运行或本构建未接线）→ 先 am start 启动应用再发命令")
        val action = intent.getStringExtra("action") ?: "dump"
        return try {
            when (action) {
                "open" -> listOf(bridge.setVisible(true))
                "close" -> listOf(bridge.setVisible(false))
                "set" -> {
                    val yaw = floatArg(intent, "yaw")
                    val pitch = floatArg(intent, "pitch")
                    if (yaw == null || pitch == null) {
                        listOf("diamond set MISSING：需要 --ef yaw <deg> --ef pitch <deg>")
                    } else if (!yaw.isFinite() || !pitch.isFinite()) {
                        listOf("diamond set INVALID：NaN/Inf 被拒（yaw=$yaw pitch=$pitch）")
                    } else listOf(bridge.setRotation(yaw, pitch))
                }
                "spin" -> {
                    val s = floatArg(intent, "speed")
                    if (s == null) listOf("diamond spin MISSING：需要 --ef speed <degPerSec>（0 = 停）")
                    else if (!s.isFinite()) listOf("diamond spin INVALID：NaN/Inf 被拒（speed=$s）")
                    else listOf(bridge.setSpin(s))
                }
                "params" -> {
                    val disp = floatArg(intent, "dispersion")
                    val ior = floatArg(intent, "ior")
                    if ((disp != null && !disp.isFinite()) || (ior != null && !ior.isFinite())) {
                        listOf("diamond params INVALID：NaN/Inf 被拒（dispersion=$disp ior=$ior）")
                    } else {
                        listOf(
                            bridge.setParams(
                                bounces = intArg(intent, "bounces"),
                                dispersion = disp,
                                ior = ior,
                                cutName = intent.getStringExtra("cut"),
                                debugMode = intArg(intent, "debug")
                            )
                        )
                    }
                }
                "dump" -> listOf(bridge.dump())
                else -> listOf("diamond ERROR unknown action='$action'；可用：open / close / set / spin / params / dump")
            }
        } catch (t: Throwable) {
            listOf("ERROR cmd=diamond action=$action ${t.javaClass.simpleName}: ${t.message}")
        }
    }
'''

insert_after(DB, "    // ---------------- 2) setSwitches ----------------", HANDLER.rstrip("\n"), "db_handler")

# ---------------- 4) 总回读 ----------------
s = read(DB)
for kw, expect in [
    ("import com.example.liquidglass.diamond.DiamondBridgeApi", 1),
    ("var diamond: DiamondBridgeApi? = null", 1),
    ('private const val CMD_DIAMOND = "diamond"', 1),
    ("CMD_FRAME_TIMELINE, CMD_DIAMOND", 1),
    ("CMD_DIAMOND -> diamond(intent)", 1),
    ("private fun diamond(intent: Intent): List<String>", 1),
    ("bridge.setParams(", 1),
]:
    c = s.count(kw)
    assert c == expect, "[final] %r 出现 %d 次（期望 %d）" % (kw, c, expect)
print("OK  db_final_check  7 项关键字全部就位")
print("ALL PATCHES APPLIED")
