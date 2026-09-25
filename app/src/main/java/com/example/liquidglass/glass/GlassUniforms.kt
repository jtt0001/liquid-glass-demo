// ---------------------------------------------------------------------------
// 移植声明 / Porting notice
// 本文件部分代码移植/改编自 QWEA0/Liquid-Glass-Android（https://github.com/QWEA0/Liquid-Glass-Android），
// 以 MIT License 授权，版权行逐字如下：
//   Copyright (c) 2025-2026 pandadog
// 许可全文见本仓库 LICENSES/ 与 THIRD_PARTY_NOTICES.md；MIT 要求版权声明与许可声明随副本一并提供，已满足。
// ---------------------------------------------------------------------------
package com.example.liquidglass.glass

/**
 * AGSL uniform 名称统一常量管理。
 *
 * 所有在 Kotlin 侧 setFloatUniform / setColorUniform 使用的名称都必须来自这里，
 * 避免手写字符串拼写错误导致 Shader uniform 静默失效（AGSL 对未设置的 uniform
 * 不报错，只会使用默认值）。
 */
object GlassUniforms {

    /** RuntimeShader 的输入 Shader uniform 名称（对应 Backdrop 捕获层内容）。 */
    const val SHADER_INPUT = "backdrop"

    // ---- 几何 ----
    /** 捕获层尺寸（px）= 卡片尺寸 + 2 × padding。注意：不能用 AGSL 内置名 resolution。 */
    const val RESOLUTION = "layerSize"
    /** 层坐标 → 卡片局部坐标的偏移（= -padding）。 */
    const val OFFSET = "offset"
    /** 卡片在窗口中的位置（px），用于环境光随位置微变。 */
    const val CARD_ORIGIN = "cardOrigin"
    /** 卡片尺寸（px）。 */
    const val CARD_SIZE = "cardSize"
    /** 四角圆角半径（px，TL/TR/BR/BL）。 */
    const val CORNER_RADII = "cornerRadii"
    /** 形状类型：0=圆角矩形 1=圆形 2=胶囊 3=椭圆 4=三角形。 */
    const val SHAPE_TYPE = "shapeType"
    /** 边缘光学作用带宽度（px）。 */
    const val EDGE_ZONE = "edgeZonePx"

    // ---- 光学参数 ----
    const val BACKGROUND_TRANSMISSION = "backgroundTransmission"
    const val MATERIAL_OPACITY = "materialOpacity"
    const val TINT_OPACITY = "tintOpacity"
    const val BLUR_RADIUS = "blurRadius"
    /** 【P64】动画期模糊降档（0=原 13t 逐像素一致；1=跳过 2.0× 外环 4 抽头并重归一）。 */
    const val BLUR_LEAN = "blurLean"
    const val EDGE_BLUR_RADIUS = "edgeBlurRadius"
    const val REFRACTION_OFFSET = "refractionOffset"
    const val REFRACTION_HEIGHT = "refractionHeight"

    // ---- iOS 透镜模型（移植自 QWEA0/Liquid-Glass-Android, MIT）----
    /** 折射剖面：>0 = 逆幂衰减指数（引力透镜），0 = 平方斜面。 */
    const val LENS_FALLOFF = "falloff"
    /** 触点局部液态凸起幅度（0..1）。 */
    const val TOUCH_AMP = "touchAmp"
    /** 折射带内沿法线方向的柔化宽度（px）。 */
    const val RIM_SOFT = "rimSoft"
    /** 1 = iOS 透镜剖面（默认），0 = 原 circleMap 剖面（Golden 对照）。 */
    const val LENS_PROFILE = "lensProfile"
    /** 1 = 逐像素自适应染色（移植，跨明暗背景不翻转），0 = 固定色调。 */
    const val ADAPTIVE_TINT = "adaptiveTint"
    /** 1 = 有色介质吸收模型（卡片），0 = 加性材质填充（面板暗底）。 */
    const val TINT_MODEL = "tintModel"
    /** 按压时的形状膨胀（px，float2）：只放大玻璃轮廓几何，不缩放图层。 */
    const val SHAPE_INFLATE = "shapeInflate"
    /** HDR 高光增益：>1 时贴边高光可超过 SDR 白点（需要窗口处于 HDR 色彩模式）。 */
    const val HDR_BOOST = "hdrBoost"

    /** 输出亮度上限（SDR 封顶 = 1.0；卡片允许 HDR = 4.0）。 */
    const val HDR_CEIL = "hdrCeil"

    /** 输出端按比例去色（控制中心"偏灰磨砂"用；卡片传 0）。 */
    const val FINAL_DESAT = "finalDesat"
    /** 色散采样偏移 px（限幅 ≤1.0dp，远小于主折射位移）。 */
    const val DISPERSION_OFFSET = "dispersionOffset"
    /** 色散作用带宽度 px（窄带，默认 10dp）。 */
    const val DISPERSION_EDGE_WIDTH = "dispersionEdgeWidth"
    /** 色散强度（0~1，默认 0.10~0.30）。 */
    const val DISPERSION_STRENGTH = "dispersionStrength"
    /**
     * 【P03·四角色散】四角权重门控（1 = 四角加权色散开；0 或未设置 = 权重退化为 1.0，不影响）。
     * 仅卡片路径（BackdropAdapter.Modifier.glass）会传 1；面板/折叠胶囊/张力桥固定传 0。
     * AGSL 侧见 GlassShaders.kt 色散段（cornerW = |nx·ny| × 双轴 smoothstep）。
     */
    const val CORNER_DISPERSION_GAIN = "cornerDispersionGain"
    const val FRESNEL_STRENGTH = "fresnelStrength"
    const val EDGE_HIGHLIGHT_OPACITY = "edgeHighlightOpacity"
    const val EDGE_SHADOW_OPACITY = "edgeShadowOpacity"
    const val LOCAL_DIMMING_OPACITY = "localDimmingOpacity"
    const val SATURATION = "saturation"
    const val ADAPTIVE_LEGIBILITY = "adaptiveLegibility"
    const val LABEL_REGION = "labelRegion"
    const val TINT_COLOR = "tintColor"
    const val MATERIAL_COLOR = "materialColor"

    // ---- 形变 ----
    const val PRESS_PROGRESS = "pressProgress"
    const val PRESS_POSITION = "pressPosition"
    const val DRAG_VELOCITY = "dragVelocity"
    const val STRETCH_DIRECTION = "stretchDirection"
    const val DEFORMATION_STRENGTH = "deformationStrength"
    /** 注意：不能用 AGSL 内置名 time。 */
    const val TIME = "animTime"

    // ---- 调试 ----
    const val DEBUG_MODE = "debugMode"
    const val PANEL_RIM_DARKEN = "panelRimDarken"

    // ---- 【P44·贴边发丝高光带支撑宽度】（未设置/0 ⇒ AGSL 侧兜底 = 旧默认 5.0 ⇒ 与改动前逐像素一致）----
    /**
     * 贴边发丝高光带的【支撑半宽倍率】（× hairScale = lgHair）—— 参数与机理见
     * [com.example.liquidglass.glass.GlassParameters.HAIR_BAND_SUPPORT_MUL]。
     *
     * 为什么走运行时 uniform（而不是像 SUPPORT_MUL 早期那样烙进 AGSL 源码）：
     *   · 让「玻璃边沿糊」的候选档能在**同一构建**上逐档扫掠 + 量化（每个候选值一次广播即可），
     *     否则每个候选值都要重新构建/装机 —— 标定成本与结论可信度都差一大截；
     *   · 回退开关 [com.example.liquidglass.debug.DebugSwitches.edgeHairBandSharp] 因此可以【运行时】翻转，
     *     ✗ 不需要重启应用（老式烙进源码的开关必须重启，见 shapeNormalSameSource 的教训）。
     * 只由 backdrop/BackdropAdapter.kt 在【卡片】的录制 lambda 里下发：
     *   面板/胶囊取 hairOld（lgCardOn=0）⇒ 本 uniform 对它们没有任何影响 ✓（它们的边缘 AA 归 panelEdgeAa 管）。
     */
    const val EDGE_HAIR_SUPPORT = "edgeHairSupport"

    // ---- 【P12·邻近流体融合】新增（未设置 = 0 ⇒ 不融合，逐像素等于改动前）----
    /** 对方卡中心（本卡节点局部坐标 px；原点 = 卡片中心，与 shader 的 centered 同源）。 */
    const val FUSE_OFFSET2 = "fuseOffset2"
    /** 对方卡半尺寸（px）。 */
    const val FUSE_HALF2 = "fuseHalf2"
    /** 对方卡圆角半径（px）。 */
    const val FUSE_RADIUS2 = "fuseRadius2"
    /** 对方卡形状类型（与 SHAPE_TYPE 同一套序数）。 */
    const val FUSE_SHAPE2 = "fuseShape2"
    /** smooth-min 融合半径（px；0 = 不融合 ⇒ AGSL 融合段整体跳过）。 */
    const val FUSE_BLEND_K = "fuseK"
    /** 颈部/细丝中心（本卡节点局部坐标 px）。 */
    const val FUSE_NECK_CENTER = "fuseNeckCenter"
    /** 颈部/细丝轴向（单位向量）。 */
    const val FUSE_NECK_AXIS = "fuseNeckAxis"
    /** 颈部/细丝半长（px）。 */
    const val FUSE_NECK_HALF = "fuseNeckHalf"
    /** 颈部/细丝半径（px；0 = 无颈部/无丝）。 */
    const val FUSE_NECK_R = "fuseNeckR"
    /** 颈部/细丝端部圆角（px；0 = 无颈部/无丝）。 */
    const val FUSE_NECK_RC = "fuseNeckRc"

    // ---- 【P07·融合 meld】归属划分开关（未设置 = 0 ⇒ 划分关闭，逐像素等于改动前）----
    /**
     * 并集区「归属划分」开关（1 = 并集区只由更近的那块卡绘制；0 = 不划分）。
     * 由 glass/DualCardMeld.kt 在每帧写入、backdrop/BackdropAdapter.kt 在录制 lambda 里下发；
     * AGSL 侧见 glass/MeldShaders.kt（门控：meldOwn>0.5 且并集场活跃且 tintModel>0.5）。
     */
    const val MELD_OWN = "meldOwn"
}
