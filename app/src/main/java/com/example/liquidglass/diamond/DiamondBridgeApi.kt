package com.example.liquidglass.diamond

/**
 * 【调试桥·3D 钻石演示】接口。
 *
 * 由屏幕层（ui/LiquidGlassScreen.kt）创建一个实现并注册到 debug/DebugBridge.kt 的 `diamond` 句柄，
 * 让 adb 广播能【确定性】驱动钻石演示（开/关、定格姿态、转速、光学参数），用于逐帧验收与 A/B 对照
 * ——合成 `input swipe` 抓不到拖动中间态，所以姿态/转速必须能被直接写入。
 *
 * 契约：
 *  · 所有方法都在【主线程】被调用（DebugBridgeReceiver.onReceive 在主线程）；
 *  · 写入的必须是 Compose 状态（或会在下一次图层重录时被读到的字段）；
 *  · 每个方法返回【一行结果】，含写入后的回读值（便于 adb 侧按回执确认，而不是靠猜）。
 */
interface DiamondBridgeApi {

    /** 打开 / 关闭演示页（等价于点击一级菜单「钻石」入口 / 返回）。 */
    fun setVisible(visible: Boolean): String

    /** 定格姿态（yaw / pitch，单位：度）——用于固定姿态抓图与前后 A/B。 */
    fun setRotation(yawDeg: Float, pitchDeg: Float): String

    /** 设定自动旋转转速（度/秒；0 = 停）。 */
    fun setSpin(degPerSec: Float): String

    /** 改光学/切工参数（null = 不改）；返回全部参数的回读行。 */
    fun setParams(
        bounces: Int?,
        dispersion: Float?,
        ior: Float?,
        cutName: String?,
        debugMode: Int?,
        envModel: Int? = null,
        trappedFix: Int? = null,
        envYFix: Int? = null
    ): String

    /** 状态一行（可见性 / 姿态 / 转速 / 面数 / 弹射 / 色散 / 尺寸）。 */
    fun dump(): String
}
