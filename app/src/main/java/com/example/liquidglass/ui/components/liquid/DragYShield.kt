/*
 * 【P50·控件拖动屏蔽 Y 轴】用户真机反馈：「拖动玻璃风格拉杆 / 底栏旋钮时，控制中心（面板或胶囊）会同步变小」
 *   —— 用户更正方向（高信度）：「应该是摇杆滑动的时候没有屏蔽 y 轴操作」。
 *
 * 【根因（逐帧实测，见 ~/Downloads/LG-yaxis-guard 报告）】P45/P46 把面板里的真实控件换成上游移植组件后，
 *   控件的拖动循环（[inspectDragGestures] / DampedDragAnimation）【只把位移喂给控件自己、从不 consume】：
 *   · 上游 drag() 的循环体里没有任何 consume（DragGestureInspector.kt 逐字移植，未动）；
 *   · 于是手指的【垂向分量】对祖先依旧「未被消费」⇒ 面板内容列表的 verticalScroll 先滚，
 *     列表滚到尽头（半屏页顶/底）后，剩余位移沿 nestedScroll 交给面板的 sheetConnection
 *     ⇒ `dispatchRawDelta` 直接改面板 offset ⇒ p 变小（Control Center 跟着收缩）；
 *   · 松手后 onPostFling 的 settle 再按【就近锚点】收口：拖得久一点（p 跌破 0.5）面板会直接
 *     吸附回胶囊 —— 真机实测：在「玻璃风格」拉杆旋钮上做一次 dy=+900px 的长下拉，
 *     p 从 1.000 掉到 0.000（w 1776→464、h 1619→124 = 收起态胶囊）✗。
 *
 * 【为什么是"回归"】改动前的自绘滑杆（ParameterSliderLegacy，仍在面板里作一行回退档）当年就专门做过这件事：
 *   「防误触……横向才接管滑杆……change.consume()」（GlassControlsPanel.kt 的 pointerInput 注释）。
 *   换成上游组件后这层保护丢了 ⇒ 本文件把「拖动期间屏蔽 Y 轴」补回给【上游控件节点】。
 *
 * 【做法（最小侵入）】在控件的拖动节点【最外层】挂一个只做「消费」的指针节点：
 *   · 位置很关键：必须排在 [InteractiveHighlight.gestureModifier] / DampedDragAnimation.modifier
 *     【之前】（= Modifier 链更靠外）⇒ 同节点的两个既有手势（旋钮拖动、触点高光跟随）
 *     先拿到事件、行为逐字不变；本节点在 Main 传递里最后一个跑，只把事件对【祖先】
 *     （列表 verticalScroll → nestedScroll → 面板 sheetConnection）标成已消费 ✓；
 *   · 只消费【移动】事件（position != previousPosition）：按下 / 抬起原样放行
 *     ⇒ 点按（滑杆跳值、底栏切页）与松手收口语义一字未改 ✓；
 *   · 不新增动画、不写状态、不产生任何绘制 ⇒ 石头不动时零像素影响 ✓。
 *
 * 【一行回退】DebugSwitches.liquidDragYShield = false（默认 true）：
 *   adb shell am broadcast -a com.liqglass.DEBUG --es cmd setSwitches \
 *       --es name liquidDragYShield --ei value 0 -p com.liqglass.ultraclear
 *   关掉后本节点整段不消费（⇐ 改前行为：Y 轴照旧漏给面板）。
 *   跨进程冷启动初值：应用私有目录文件 `files/lg_drag_yshield_off`（内容 "1" = 关）。
 *
 * 【范围】✗ 不动 glass 包的折射默认参数、✗ 不动面板几何（p 驱动的那套）、✗ 不动交棒区、
 *   ✗ 不动上游 DragGestureInspector / DampedDragAnimation / InteractiveHighlight 的任何逻辑
 *   （它们是逐字移植件，本文件只在调用点外侧加一个节点）。
 */
package com.example.liquidglass.ui.components.liquid

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.example.liquidglass.debug.DebugSwitches

/**
 * 拖动期间把指针位移标记为「已消费」，阻断垂向分量继续上抛到面板/列表（详见文件头注释）。
 *
 * 必须挂在控件拖动节点上、且排在其它指针 Modifier【之前】（更靠链外）：
 * 同节点内的既有手势先执行，本节点最后执行 ⇒ 只影响祖先，不影响本控件的既有手势。
 */
internal fun Modifier.liquidDragYShield(): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            // 不抢 down：requireUnconsumed=false，按下事件原样放行给同节点/祖先的既有处理
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                // 抬起（或全部松手）⇒ 本段手势结束；up 事件不消费（松手收口/点按判定沿用原逻辑）
                if (event.changes.none { it.pressed }) break
                // 【开关】普通 @Volatile 字段，逐事件读取 ⇒ 运行时翻开关立即生效（无需重组/重启）
                if (!DebugSwitches.liquidDragYShield) continue
                for (change in event.changes) {
                    if (change.pressed && change.position != change.previousPosition) {
                        change.consume()
                    }
                }
            }
        }
    }
