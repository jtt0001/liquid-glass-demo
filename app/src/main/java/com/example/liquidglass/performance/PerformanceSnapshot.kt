package com.example.liquidglass.performance

/**
 * 性能快照（不可变）。
 *
 * 字段说明：
 * - 帧指标来自 Window.OnFrameMetricsAvailableListener（FrameMetrics，纳秒）。
 * - GPU_DURATION 不可用（getMetric 返回 -1）时，gpu 字段为 null，UI 显示 N/A；
 *   不得把 TOTAL_DURATION / COMMAND_ISSUE_DURATION 伪装成真实 GPU 耗时，
 *   它们只能作为 Proxy 附加展示。
 * - 电池 CURRENT_NOW 原始单位为 µA（转换为 mA）；CHARGE_COUNTER 原始单位为 µAh。
 *   会话能耗 = 电流 × 时间 的积分（mAh），瞬时值不存在“mAh”这种表述。
 * - 设备不支持电池属性时字段为 null，显示 N/A，不得显示为 0。
 */
data class PerformanceSnapshot(
    /** 最近 1 秒帧数（静止无新帧时为 null，UI 显示 IDLE）。 */
    val fps: Float?,
    /** 平均帧耗时 ms。 */
    val avgFrameMs: Float?,
    /** P95 帧耗时 ms。 */
    val p95FrameMs: Float?,
    /** 超过刷新周期一倍的帧数（jank）。 */
    val jankCount: Int,
    /** 本进程 CPU 占用（按全部逻辑核心归一化的百分比）。 */
    val appCpuPercent: Float?,
    /** GPU 帧耗时中位数 ms（GPU_DURATION，不可用时 null）。 */
    val gpuMedianMs: Float?,
    /** GPU 帧耗时 P95 ms（不可用时 null）。 */
    val gpuP95Ms: Float?,
    /** Proxy：总帧耗时中位数 ms。 */
    val totalMedianMs: Float?,
    /** Proxy：命令提交耗时中位数 ms。 */
    val issueMedianMs: Float?,
    /** 当前电池电流 mA（正=放电，负=充电；不支持时 null）。 */
    val batteryCurrentMa: Float?,
    /** 页面会话累计能耗 mAh（不支持时 null）。 */
    val sessionEnergyMah: Float?,
    /** 是否正在充电。 */
    val charging: Boolean?,
    /** 【P65 修复①回读】本次判定 jank 采用的阈值 ms（= 当前刷新周期）。 */
    val jankThresholdMs: Float?,
    /** 【P65 修复①回读】判定时采用的刷新率 Hz（RefreshRateController 当前档位值 / Display 兜底）。 */
    val refreshHz: Float?,
    /** 【P65 修复①回读】Display.getRefreshRate() 实测 Hz（与 refreshHz 不同 ⇒ 当前档为【模拟值】）。 */
    val displayHz: Float?,
    /** 快照时间戳 ms。 */
    val timestampMs: Long
) {

    companion object {
        /** 初始空快照。 */
        fun idle(): PerformanceSnapshot = PerformanceSnapshot(
            fps = null, avgFrameMs = null, p95FrameMs = null, jankCount = 0,
            appCpuPercent = null, gpuMedianMs = null, gpuP95Ms = null,
            totalMedianMs = null, issueMedianMs = null,
            batteryCurrentMa = null, sessionEnergyMah = null, charging = null,
            jankThresholdMs = null, refreshHz = null, displayHz = null,
            timestampMs = 0L
        )
    }
}
