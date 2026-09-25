package com.example.liquidglass.diamond

import java.util.Locale

/**
 * 【3D 钻石演示 · 调试桥实现】把 adb 广播命令翻译成 [DiamondDemoState] 的写操作，并返回【含回读值】的一行结果。
 *
 * 契约（见 diamond/DiamondBridgeApi.kt）：
 *  · 全部方法在【主线程】被调用（DebugBridgeReceiver.onReceive）；
 *  · 每个方法返回一行，含写入后的回读值（adb 侧按回执确认，不靠猜）；
 *  · 数值越界一律 coerceIn；✗ 对 NaN / Inf 【拒绝】并返回 INVALID 一行（Float.coerceIn 对 NaN 放行，
 *    本项目踩过"写 NaN 把几何报废"的坑）——校验不通过时【一个字段都不写】。
 */
class DiamondDemoBridge(private val state: DiamondDemoState) : DiamondBridgeApi {

    /** 打开 / 关闭演示页。 */
    override fun setVisible(visible: Boolean): String {
        state.visible = visible
        return "diamond visible=" + if (state.visible) "on" else "off"
    }

    /** 定格姿态：清惯性速度（yaw 归一化到 (-180,180]，pitch 的 ±89° 钳制在 state 里）。 */
    override fun setRotation(yawDeg: Float, pitchDeg: Float): String {
        if (!yawDeg.isFinite() || !pitchDeg.isFinite()) {
            return invalid("setRotation", "yaw=$yawDeg pitch=$pitchDeg")
        }
        state.setRotation(yawDeg, pitchDeg)
        return String.format(
            Locale.US,
            "diamond rotation yaw=%.2f pitch=%.2f（已定格；惯性速度清零）",
            state.yawDeg,
            state.pitchDeg
        )
    }

    /** 设转速（度/秒；0 = 停）。 */
    override fun setSpin(degPerSec: Float): String {
        if (!degPerSec.isFinite()) return invalid("setSpin", "speed=$degPerSec")
        val clamped = degPerSec.coerceIn(-DIAMOND_MAX_SPIN_DEG_PER_SEC, DIAMOND_MAX_SPIN_DEG_PER_SEC)
        state.setSpin(clamped)
        val note = if (clamped != degPerSec) "（越界已 clamp：$degPerSec → $clamped）" else ""
        return String.format(Locale.US, "diamond spin=%.2f degPerSec%s", state.spinDegPerSec, note)
    }

    /**
     * 改光学 / 切工参数（null = 不改）；返回全部参数的回读行。
     *
     * 校验顺序：① 浮点参数 NaN/Inf → 直接 INVALID 且不写任何字段；
     *           ② 切工名不认识 → REJECT 一行并列出可选名（不静默忽略）；
     *           ③ 其余数值越界一律 coerceIn（并在回读行里注明 clamp 过）。
     */
    override fun setParams(
        bounces: Int?,
        dispersion: Float?,
        ior: Float?,
        cutName: String?,
        debugMode: Int?,
        envModel: Int?,
        trappedFix: Int?,
        envYFix: Int?
    ): String {
        if (dispersion != null && !dispersion.isFinite()) {
            return invalid("params", "dispersion=$dispersion")
        }
        if (ior != null && !ior.isFinite()) {
            return invalid("params", "ior=$ior")
        }
        val cut = if (cutName == null) null else DiamondCut.byName(cutName)
        if (cutName != null && cut == null) {
            return "diamond params REJECT cut=$cutName（不认识；可选：${cutNames()}）"
        }

        val notes = ArrayList<String>(4)
        bounces?.let {
            val v = it.coerceIn(0, DIAMOND_MAX_BOUNCES)
            if (v != it) notes.add("bounces $it→$v")
            state.bounces = v
        }
        dispersion?.let {
            val v = it.coerceIn(0f, DIAMOND_MAX_DISPERSION)
            if (v != it) notes.add("dispersion $it→$v")
            state.dispersion = v
        }
        ior?.let {
            val v = it.coerceIn(DIAMOND_MIN_IOR, DIAMOND_MAX_IOR)
            if (v != it) notes.add("ior $it→$v")
            state.ior = v
        }
        debugMode?.let {
            val v = it.coerceIn(0, DIAMOND_MAX_DEBUG_MODE)
            if (v != it) notes.add("debug $it→$v")
            state.debugMode = v
        }
        envModel?.let {
            val v = it.coerceIn(0, 1)
            if (v != it) notes.add("envModel $it→$v")
            state.envModel = v
        }
        trappedFix?.let {
            val v = it.coerceIn(0, 1)
            if (v != it) notes.add("trappedFix $it→$v")
            state.trappedFix = v
        }
        envYFix?.let {
            val v = it.coerceIn(0, 1)
            if (v != it) notes.add("envYFix $it→$v")
            state.envYFix = v
        }
        cut?.let { state.cut = it }

        val line = String.format(
            Locale.US,
            "diamond params bounces=%d dispersion=%.4f ior=%.3f env=%d trapped=%d envY=%d cut=%s faces=%d debug=%d",
            state.bounces,
            state.dispersion,
            state.ior,
            state.envModel,
            state.trappedFix,
            state.envYFix,
            state.cut.name,
            state.cut.facetCount,
            state.debugMode
        )
        return if (notes.isEmpty()) line else line + "（clamp：${notes.joinToString(" ")}）"
    }

    /** 状态一行（与 [DiamondDemoState.dumpLine] 同源）。 */
    override fun dump(): String = state.dumpLine()

    // ===================== 内部 =====================

    /** 拒绝行：入口没有写任何字段（调用方可直接断言 INVALID）。 */
    private fun invalid(where: String, detail: String): String =
        "diamond $where INVALID：NaN/Inf 被拒（$detail）；未写入任何字段"

    /** 可选切工名（REJECT 行里给 adb 侧看；含枚举名与界面显示名）。 */
    private fun cutNames(): String =
        DiamondCut.entries.joinToString(",") { "${it.name}（${it.displayName}）" }
}
