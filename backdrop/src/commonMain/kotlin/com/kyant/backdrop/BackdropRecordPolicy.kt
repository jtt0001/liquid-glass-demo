package com.kyant.backdrop

/**
 * ============================================================================
 * 【分层架构 · 录制策略】—— 本项目对 vendored backdrop 库的私有扩展
 * ============================================================================
 * 目标态（见 01-源码-主线/LAYERING.md 的「分层图」）：
 *   背景层 → 卡0 → 卡1 … → 卡N → 面板层 →（看板：纯 UI，不采样）
 *
 * 两条硬规则（都由本文件驱动；默认全开 = 目标态；一行回退 = 置 false）：
 *
 *   ① [singleRecordPerFrame] 同一帧里，一个玻璃节点自己的离屏层只录【一次】。
 *      改前：`DrawBackdropNode.draw()` 里 `drawBackdropLayer()`（录制 + 绘制）被调用两次
 *      —— 一次给主画布、一次在 `exportedBackdrop` 的重放块里 —— 而重放块里的那次
 *      会把【同一份内容】重新录进同一个 GraphicsLayer ⇒ 每帧 2 次重复录制（白费一次
 *      代价最高的 AGSL 录制）。置 false = 逐字回到改前行为（重放块里重新录制）。
 *      像素等价性：两次录制的输入（uniform/采样层内容）在同一帧内完全一致 ⇒ 输出逐像素相同。
 *
 *   ② [recordOnlyWhenSampled] 没人采样的层不做离屏录制（首帧除外，见下）。
 *      改前：`LayerBackdropNode.draw()` 无条件 `recordLayer` ⇒ 只要该节点被绘制，
 *      就把自己的渲染结果录进离屏层 —— 包括【全工程没有任何采样方】的层
 *      （默认档 A 的 panelCaptureLayer：卡在面板之下时卡的采样源里没有面板）。
 *      现在：首帧先录一次（避免"层从未有过内容 ⇒ 采样方看到空层/黑玻璃"，这是
 *      本项目"黑卡修复"踩过的坑），之后只有【被采样过】的层才继续录。
 *      置 false = 逐字回到改前行为（无条件录制）。
 *
 * 运行时控制（无需重编译）：`adb shell am broadcast -a com.liqglass.DEBUG --es cmd layerStack …`
 * （见 debug/DebugBridge.kt 的 layerStack 命令：--ei single 0/1 --ei sampled 0/1 --ei probe 0/1
 *  --ei reset 1  ⇒ 改开关 / 重置探针 / 打印逐层录制统计）。
 */

object BackdropRecordPolicy {

    /** ① 单次录制（默认开）。关 = 改前行为：exportedBackdrop 重放块里重新录制玻璃层。 */
    @Volatile
    var singleRecordPerFrame: Boolean = true

    /** ② 按需录制（默认开）。关 = 改前行为：节点每次绘制都无条件录制。 */
    @Volatile
    var recordOnlyWhenSampled: Boolean = true

    fun describe(): String =
        "singleRecordPerFrame=$singleRecordPerFrame" +
            " recordOnlyWhenSampled=$recordOnlyWhenSampled" +
            " probe=${BackdropRecordProbe.enabled}"
}

/**
 * 【分层架构 · 录制探针】逐层统计【录制次数 / 最近一次录制尺寸 / 角色标签】。
 *
 * 用途：验收表「每帧各层的录制次数 / 尺寸」（改前 vs 改后）。
 * 用法：`layerStack --ei probe 1 --ei reset 1` → 做动作（如面板开合 / 拖动卡片）
 *      → `layerStack --ei reset 0`（只打印不重置）读出计数 → 用 `dumpsys gfxinfo`
 *      的「Total frames rendered」当分母 ⇒ 每帧录制次数。
 *
 * 契约（重要）：
 *  · 关闭时零开销（一次 @Volatile 读）；开启时每次录制做一次线性查找（层数量级 < 20）。
 *  · 只在【绘制线程】调用（Compose 的 draw / recordLayer 通路）⇒ 不加锁；
 *    探针只读统计、不改变任何录制/绘制行为 ⇒ 对像素与帧率无影响（除打印时）。
 *  · 标签由调用点给出（capture / glass / export），身份用引用相等（===）判定。
 */
object BackdropRecordProbe {

    @Volatile
    var enabled: Boolean = false

    // 绘制线程单线程语义：不做同步（见上方契约）。
    private val ids = ArrayList<Any>(16)
    private val tags = ArrayList<String>(16)
    private val counts = ArrayList<Long>(16)
    private val lastW = ArrayList<Int>(16)
    private val lastH = ArrayList<Int>(16)
    private var total = 0L

    /** 调用点：每次 `recordLayer` 都会走这里（关闭时立即返回）。 */
    fun onRecord(layer: Any, tag: String, width: Int, height: Int) {
        if (!enabled) return
        var idx = -1
        for (i in ids.indices) {
            if (ids[i] === layer) {
                idx = i
                break
            }
        }
        if (idx < 0) {
            idx = ids.size
            ids.add(layer)
            tags.add(tag)
            counts.add(0L)
            lastW.add(0)
            lastH.add(0)
        }
        counts[idx] = counts[idx] + 1L
        lastW[idx] = width
        lastH[idx] = height
        total += 1L
    }

    fun reset() {
        ids.clear(); tags.clear(); counts.clear(); lastW.clear(); lastH.clear()
        total = 0L
    }

    fun totalRecords(): Long = total

    /** 逐层一行：`L<i> tag=<capture|glass|export> count=<n> size=<w>x<h>`（多行）。 */
    fun dump(): String {
        if (ids.isEmpty()) return listOf("layerProbe: 无录制记录（probe=$enabled；先做动作再 dump）").joinToString("\n")
        val sb = StringBuilder()
        for (i in ids.indices) {
            sb.append("L").append(i)
                .append(" tag=").append(tags[i])
                .append(" count=").append(counts[i])
                .append(" size=").append(lastW[i]).append("x").append(lastH[i])
            if (i != ids.lastIndex) sb.append('\n')
        }
        sb.append("\ntotal=").append(total)
        return sb.toString()
    }
}

/**
 * 【规则②】是否应该录制本层。
 *
 * @param everRecorded 本层是否至少录过一次（首帧必录 ⇒ 采样方不会看到空层）
 * @param everSampled  本层是否被采样过（= 采样序号 > 0）
 */
internal fun shouldRecordLayer(everRecorded: Boolean, everSampled: Boolean): Boolean {
    if (!BackdropRecordPolicy.recordOnlyWhenSampled) return true
    return !everRecorded || everSampled
}
