package com.example.liquidglass.debug

import androidx.compose.foundation.ScrollState

/**
 * 【P43 取证·只读探针】面板内容「列表槽高 vs 实际内容高 vs 滚动视口」。
 *
 * 背景（用户真机反馈②「拉到半屏的时候显示不全」）：二级页内容槽高历史上被钉在
 * `contentPageSlotPx()`（= p=2 档面板高），但半屏（p=1）时面板本身只有 1619px（模拟器
 * 1840×2944）⇒ 需要一份【运行时读数】才能判断"显示不全"到底卡在哪一层：
 *   · 槽高 < 内容高 ⇒ 内容被裁（槽高不足）；
 *   · 滚动视口高 > 可见高 ⇒ 滚动范围不足（列表滚到底也看不到最后几节）。
 *
 * 本对象只做三件事：① 记住列表的 ScrollState（dump 时读 value/maxValue/viewportSize）；
 * ② 记住滚动节点自身的尺寸（= 视口高）；③ 记住其内容尺寸（= 内容实际高，由 verticalScroll
 * 子节点尺寸给出）。全部是普通 @Volatile 字段读写（非快照状态）⇒ 零重组、零重绘、
 * 对像素/布局/性能零影响 ✓（只在 debug 构建的 dumpState 文本里被读）。
 */
object ListSlotProbe {

    @Volatile
    private var state: ScrollState? = null

    /** 滚动视口高（px）：由 verticalScroll【之外】的 onSizeChanged 写入。 */
    @Volatile
    var viewportPx: Int = -1

    /** 列表内容实际高（px）：由 verticalScroll【之内】的 onSizeChanged 写入。 */
    @Volatile
    var contentPx: Int = -1

    /** 【P43】layout 阶段实际算出的"列表底部余量"（px）：由 GlassControlsPanel 的 layout 修饰符写入。 */
    @Volatile
    var padPx: Int = -1

    fun attach(s: ScrollState?): Unit {
        state = s
    }

    /** dumpState 一行（未组合时明确写出，避免误读为 0）。 */
    fun line(): String {
        val s = state ?: return "listslot   = （列表未组合 / 未 attach；padPx=$padPx）"
        val content = if (contentPx > 0) contentPx else s.viewportSize + s.maxValue
        return ("listslot   = viewport=%d maxScroll=%d scroll=%d content=%d " +
            "viewportH=%d contentH=%d pad=%d 可滚范围=[0,%d]")
            .format(
                s.viewportSize, s.maxValue, s.value, content,
                viewportPx, contentPx, padPx, s.maxValue
            )
    }
}
