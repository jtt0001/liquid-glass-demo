// ---------------------------------------------------------------------------
// 移植声明 / Porting notice
// 本文件部分代码移植/改编自 QWEA0/Liquid-Glass-Android（https://github.com/QWEA0/Liquid-Glass-Android），
// 以 MIT License 授权，版权行逐字如下：
//   Copyright (c) 2025-2026 pandadog
// 许可全文见本仓库 LICENSES/ 与 THIRD_PARTY_NOTICES.md；MIT 要求版权声明与许可声明随副本一并提供，已满足。
// ---------------------------------------------------------------------------
package com.example.liquidglass.glass

import com.example.liquidglass.debug.DebugSwitches

/**
 * AGSL Shader 源码管理与缓存 key（第三阶段：Color Pipeline 结构化重构）。
 *
 * 管线（四段式，严格按阶段诊断规范）：
 *
 *   1. Background Capture —— Backdrop 捕获原始背景（本项目由 backdrop 模块
 *      LayerBackdrop 完成，本 Shader 只消费 backdrop 输入，绝不反向修改捕获源）。
 *   2. Refraction Pass    —— Golden Reference：SDF → surface normal → 唯一
 *      refractedCoord。此段在第二阶段已验证正确，本轮冻结，禁止重写。
 *   3. Achromatic Blur    —— 围绕【同一个】refractedCoord 做无色散模糊：
 *      所有 tap 的 R/G/B 使用完全相同的采样坐标，权重归一化。
 *   4. Edge Dispersion    —— 独立边缘探针：只在 edgeBand 窄带内计算
 *      chromaDelta（探针 RGB 重组 - 中心采样），加到最终颜色；Alpha 不分通道。
 *
 * 调试模式（GlassDebugMode）：BACKGROUND_ONLY / REFRACTION_ONLY /
 * ACHROMATIC_BLUR_ONLY / DISPERSION_ONLY / FINAL。
 *
 * 圆角矩形 SDF（radiusAt / sdRoundedRect / gradSdRoundedRect）与 circleMap
 * 改编自 Backdrop 库（Apache-2.0，作者 Kyant）的 AGSL 源码，见 NOTICE.md 与
 * LICENSES/Backdrop-APACHE-2.0.txt。
 */
object GlassShaders {

    private const val KEY_PREFIX = "UltraClearGlass"

    /** Shader 缓存 key：只与画质档位相关（5/9/13 taps），参数变化不换 key。 */
    fun cacheKey(quality: GlassQuality): String = "${KEY_PREFIX}_${quality.name}"

    /**
     * 控制中心面板/椭圆按钮的 Shader 缓存 key。
     * 与卡片同源 AGSL 但独立实例——两边 uniform 组合完全不同，
     * 共用实例会互相覆写 uniform（面板与卡片同时存在于屏幕上）。
     */
    /**
     * 液态张力桥的独立缓存 key：与卡片/面板分开缓存，
     * 否则同屏多个元素会共用同一个 Shader 实例、uniform 互相覆写。
     */
    fun bridgeCacheKey(quality: GlassQuality): String = "liquidGlassBridge_" + quality.name

    fun panelCacheKey(quality: GlassQuality): String = "${KEY_PREFIX}Panel_${quality.name}"

    /**
     * 【一键回退开关·尺寸自适应带宽】—— 修复用户报的「三角形玻璃调节过小会产生畸变」。
     *
     * 根因（实测 + 解析几何；下游用法见 MAIN 里的 edgeMask / lensThickness / refrHeight /
     * dispBase / blurR / sm / dispVec / bandW / rimW / hair / glow 各行）：
     * 边缘作用带 / 折射剖面高度 / 折射位移 / 模糊半径 / 高光发丝与辉光 / 色散带
     * **全部是固定 px**（= dp × density，与形状尺寸无关；换算点在
     * UltraClearGlassEffect.compute 与 BackdropAdapter 的 EDGE_ZONE 处）。
     * 形状越小，这些带宽相对形状就越宽；小到一定程度后「带子比形状还大」⇒
     * 同一像素同时落在多条边带里 ⇒ 折射/高光/色散自交 ⇒ 观感即"畸变"。
     * 实测（模拟器 1840×2944@320dpi，density=2；三角卡片为正方，边长 = glassSize × 1840）：
     *   0.20 档 卡片 368×368，三角内切半径 92.3px、顶点 56.6°；边缘作用带 uniform =
     *        24dp×2.1×2 = 100.8px ⇒ **带/半径 = 1.09（带子比形状还大）**；顶点处两条斜边带的
     *        重叠深度 = 100.8 / sin(28.3°) = 213px = 高度的 74%；折射位移 64px = 0.69×内切半径
     *   0.35 档 卡片 644×644，内切半径 161.6px，带/半径 = 0.62，重叠 = 高度的 42%
     *   0.50 档 卡片 920×920，内切半径 230.8px，带/半径 = 0.44，重叠 = 高度的 30%
     * ⇒ 带子占比与"自交"区随尺寸变小急剧放大（缩放系数 0.575 / 1.0 / 1.0），与用户描述一致；
     *   模拟器实测（0.20 档三角形玻璃掩膜）底边宽 313px ↔ 理论 309px、掩膜高 299px ↔ 理论 287px ✓
     *
     * true（默认）= 带宽随形状尺寸【按比例缩放并 clamp】：
     *   ① lgScale = clamp(短边 / REF, MIN_SCALE, 1.0) —— 短边 ≥ REF 时恒为 1.0
     *      ⇒ 大/中尺寸（0.50 / 0.35 档）逐像素不变 ✓
     *   ② 带宽 ≤ 短边 × BAND_MAX_FRAC（lgBandCeil）—— 任何尺寸下带子都不可能宽于形状 ✓
     * false = 完全回退到固定 px 带宽（= 修改前行为）：[SIZE_ADAPTIVE_CODE] 退化为恒等
     * （lgScale = 1.0、lgBandCeil = ∞），所有带宽表达式与修改前逐字符等价。要回退只需把
     * 本常量改成 false 重新构建（改一行，无需碰 AGSL 源码）。
     */
    const val SIZE_ADAPTIVE_BANDS: Boolean = true

    /**
     * 【本级的补充·细粒度「内切圆 clamp」】—— 参数与回退开关见 [GlassParameters]：
     * [GlassParameters.SHAPE_INRADIUS_CLAMP] / SHAPE_INRADIUS_BAND_FRAC /
     * SHAPE_INRADIUS_HIGHLIGHT_FRAC / SHAPE_INRADIUS_FEATHER_FRAC。
     *
     * 两级修复可独立开关：
     *   · [SIZE_ADAPTIVE_BANDS]（粗粒度）= 全部带宽按【短边比例】缩放 —— 管"整体都变小"；
     *   · [GlassParameters.SHAPE_INRADIUS_CLAMP]（细粒度）= 折射带高 / 棱镜高光宽 / 边界羽化
     *     再受【形状内切圆半径 × 分数】上限约束，只对三角形卡片生效 —— 管"带子不许宽过形状的芯"。
     * 两者都默认为 true；任一处置 false 即整段退化（生成的回退片段 = 恒等变换，逐值等价：
     * 文本上多了 min(..., 1e9) / max(..., 1.0) 两层，取值与改动前完全相同）。
     */

    /** 尺寸自适应的参考尺寸 px：形状【短边】≥ 该值 ⇒ 缩放系数恒为 1.0。
     *  取 640px ≈ 本机（1840×2944@320dpi）默认档 0.35 的卡片短边 644px。 */
    private const val SIZE_REF_PX = 640f

    /** 缩放系数下限：极小卡片下带宽仍保留该比例，避免带子被压没、边缘失去光学特征。 */
    private const val SIZE_MIN_SCALE = 0.30f

    /** 带宽上限系数：任何带宽 ≤ 形状短边 × 本系数（0.40 = 短边 40%，永不宽于形状）。 */
    private const val SIZE_BAND_MAX_FRAC = 0.40f

    /**
     * 尺寸自适应片段的 AGSL 源码（插在 main 开头；由 [source] 按 [SIZE_ADAPTIVE_BANDS] 拼接）。
     * 两个分支定义的变量名完全相同 ⇒ 下游带宽表达式只需写一份（回退档 = 恒等变换）。
     */
    private val SIZE_ADAPTIVE_CODE: String = run {
        fun f(v: Float): String = "%.4f".format(java.util.Locale.US, v)
        val head = """
    // ---- 尺寸自适应带宽（缺陷修复：极小尺寸下"带子比形状还大" → 折射/高光自交 → 畸变）----
    // 带宽类参数全是固定 px（与形状尺寸无关）；形状变小时必须同步缩小，否则边缘带/
    // 折射带/高光带会在同一像素上叠加。这里算一次尺度，供本函数内全部带宽使用：
    //   lgScale    = clamp(短边/参考, 下限, 1.0)  ← 短边 ≥ 参考尺寸时恒为 1.0（大尺寸逐像素不变）
    //   lgBandCeil = 短边 × 上限系数              ← 兜底：任何尺寸下带子都不会宽于形状
    float lgSizeMin = max(min(cardSize.x, cardSize.y), 1.0);
    // 【只对演示卡片生效】tintModel 是"卡片(1)/面板(0)"的既有标记（GlassUniforms 注释即此语义）：
    // 面板与胶囊是屏幕级 UI（尺寸由布局决定、用户不可调），其带宽必须保持原样 ⇒ 不参与缩放，
    // 否则折叠胶囊（652×174）会因短边 174 < 参考尺寸而被误缩到 30% ✗（默认态硬门会报警）。
    float lgCardOn = step(0.5, tintModel);
    float lgScale = mix(1.0, clamp(lgSizeMin / ${f(SIZE_REF_PX)}, ${f(SIZE_MIN_SCALE)}, 1.0), lgCardOn);
    float lgBandCeil = mix(1000000000.0, lgSizeMin * ${f(SIZE_BAND_MAX_FRAC)}, lgCardOn);
"""
        val fallback = """
    // 【回退档】尺寸自适应关闭：lgScale = 1.0、lgBandCeil = ∞ ⇒ 下游全部退化为固定 px 行为
    float lgSizeMin = max(min(cardSize.x, cardSize.y), 1.0);
    float lgCardOn = 1.0;
    float lgScale = 1.0;
    float lgBandCeil = 1000000000.0;
"""
        val inradius = if (GlassParameters.SHAPE_INRADIUS_CLAMP) """
    // ---- 【形状内切圆 clamp】折射带高 / 棱镜高光宽 / 边界羽化 的尺寸上限（= 内切圆半径 × 分数）----
    // 用户缺陷：「三角形玻璃调节过小会产生畸变」——带子按固定 px 取，形状一变小就"带子比形状还大"，
    // 三条边带在内心附近重叠自交 ⇒ 掩码饱和、边缘项重复计入 ⇒ 亮部折叠 + 顶点硬锯齿。
    // 内切圆半径按与 sdTriangleShape **完全同源**的顶点解析求出（同一个三角形，不另写一套顶点 ✗）：
    //   p0=(0,-0.72hy) p1=(-0.84hx,0.84hy) p2=(0.84hx,0.84hy)（hx/hy = 卡片半宽/半高）
    //   底 a = 1.68hx、两腰 b = c = sqrt(0.7056hx² + 2.4336hy²)、半周长 s = 0.84hx + b
    //   面积 A = 0.5·1.68hx·1.56hy = 1.3104·hx·hy ⇒ 内切半径 r_in = A / s
    // 三条边带的自交临界尺度正是 r_in：带子一接近它，三条带就会在内心附近叠在一起。
    float lgrHalfX = max(cardSize.x * 0.5, 1.0);
    float lgrHalfY = max(cardSize.y * 0.5, 1.0);
    float lgrLeg = sqrt(0.7056 * lgrHalfX * lgrHalfX + 2.4336 * lgrHalfY * lgrHalfY);
    float lgrInradius = (1.3104 * lgrHalfX * lgrHalfY) / (0.84 * lgrHalfX + lgrLeg);
    // 只对【三角形演示卡片】生效：shapeType 4 = 三角形、tintModel>0.5 = 卡片（面板 0 / 张力桥 0）
    // 其余形状与面板/桥 ⇒ 上限 = ∞ ⇒ 与改动前逐像素一致
    float lgrOn = (shapeType == 4 && tintModel > 0.5) ? 1.0 : 0.0;
    float lgrInr = mix(1000000000.0, lgrInradius, lgrOn);
    // 大尺寸下三个上限都大于「原常量」⇒ 下游 min() 取回原常量 = 逐像素不变 ✓（余量表见 GlassParameters）
    float lgTriBandCeil = lgrInr * ${f(GlassParameters.SHAPE_INRADIUS_BAND_FRAC)};
    float lgTriHiCeil = lgrInr * ${f(GlassParameters.SHAPE_INRADIUS_HIGHLIGHT_FRAC)};
    float lgTriFeatherCeil = lgrInr * ${f(GlassParameters.SHAPE_INRADIUS_FEATHER_FRAC)};
""" else """
    // 【回退档·内切圆 clamp 关闭（GlassParameters.SHAPE_INRADIUS_CLAMP = false）】
    // 三个上限 = ∞ ⇒ 下游 min() 退化为恒等变换 = 与改动前逐值等价（表达式文本多了两层 min/max）
    float lgTriBandCeil = 1000000000.0;
    float lgTriHiCeil = 1000000000.0;
    float lgTriFeatherCeil = 1000000000.0;
"""
        val body = """    float lgEdgeZone = min(edgeZonePx * lgScale, lgBandCeil);
    float lgRefrHeight = max(min(min(refractionHeight * lgScale, lgBandCeil), lgTriBandCeil), 1.0);   // 折射带高：叠加「内切圆半径 × kBandFrac」上限
    float lgRefrOffset = min(refractionOffset * lgScale, lgBandCeil);
    float lgBlurRadius = min(blurRadius * lgScale, lgBandCeil);
    float lgEdgeBlurRadius = min(edgeBlurRadius * lgScale, lgBandCeil);
    float lgRimSoft = min(rimSoft * lgScale, lgBandCeil);
    float lgDispWidth = min(dispersionEdgeWidth * lgScale, lgBandCeil);
    float lgDispOffset = min(dispersionOffset * lgScale, lgBandCeil);
    float lgFeather = max(min(clamp(5.0 * lgScale, 2.0, 5.0), lgTriFeatherCeil), 1.0);      // 边界羽化：随尺寸缩放（2~5px）+ 内切圆上限（下限 1px 防硬边）
    float lgHair = clamp(1.5 * lgScale, 1.0, 1.5);         // 贴边发丝亮线（下限 1px：保证可见 + AA）
    float lgGlow = clamp(3.0 * lgScale, 1.5, 3.0);         // 迎光侧内辉光尺度
"""
        (if (SIZE_ADAPTIVE_BANDS) head else fallback) + inradius + body
    }

    /**
     * 【锯齿修复③】贴边发丝高光带的剖面（AGSL 片段；由 [source] 替换 MAIN 里的
     * [HAIR_BAND_TOKEN] 占位符）。参数与机理见 [GlassParameters.HAIR_BAND_EDGE_AA]。
     *
     * 两个分支：开 = 单峰平滑剖面（外侧裙摆 OUT×hairScale 从边界 0 平滑升起）。
     *           关 = 与修改前【逐字符等价】的旧三角剖面（一行）。
     * 两者都定义同一个 `float hair`，且【只对演示卡片生效】（mix(..., lgCardOn)）：
     * 面板/胶囊（tintModel=0 ⇒ lgCardOn=0）取旧值 ⇒ 逐像素不变 ✓。
     *
     * 【①A 增量·三角形高光带内缩】两个分支的 `sd` 都加上 `lgTriHairInset`
     * （= k×hairScale，仅三角形演示卡片非零；见 [GlassParameters.TRIANGLE_HAIR_INSET]）：
     * 把带整体向形状内侧平移，使可见亮带完全落在形状内侧（原本骑在边界上 ✗）。
     * 开关关档里 inset 恒为 0.0 ⇒ 与改动前逐值等价。
     */
    private val HAIR_BAND_CODE: String = run {
        fun f(v: Float): String = "%.4f".format(java.util.Locale.US, v)
        // 【①A】三角形专用内缩量（无开关档 = 恒 0.0；只对 shapeType==4 的演示卡片非零）
        val inset = if (GlassParameters.TRIANGLE_HAIR_INSET) """
    // 【①A 三角形高光带内缩】仅【三角形演示卡片】：把发丝带整体沿法向向形状内侧平移。
    // 原状：带中心只在内侧 0.5·hairScale（0.75px），支撑 ±5·hairScale ⇒ 带的一半在形状外 ✗
    //（= 骑在可见边界上）。外侧半边的可见量被 coverage 羽化截断，且"看起来多宽"随
    //【边方向 ↔ 像素网格】变化 ⇒ 三条边亮宽不一致（用户：小尺寸时不像正三角形 ✗）。
    // 内缩 k = ${f(GlassParameters.TRIANGLE_HAIR_INSET_MUL)}×hairScale 后，半高区
    // 落在 [−(3+k)·hairScale, −(k−2)·hairScale] ⇒ k=2 时外沿半高正好落在可见边界 sd=0 上 ✓
    float lgTriOn = (shapeType == 4 && tintModel > 0.5) ? 1.0 : 0.0;
    float lgTriHairInset = lgTriOn * (${f(GlassParameters.TRIANGLE_HAIR_INSET_MUL)} * hairScale);
""" else """
    // 【回退档·GlassParameters.TRIANGLE_HAIR_INSET = false】内缩恒为 0 ⇒ 与改动前逐值等价
    float lgTriHairInset = 0.0;
"""
        if (GlassParameters.HAIR_BAND_EDGE_AA) """
    // 【锯齿修复③】贴边发丝带：把三角剖面的【折点】换成 smoothstep 平滑过渡。
    // 支撑区间 = SUPPORT×hairScale（半宽 ≈1.05px，落在要求的 1.0~1.25px 内）、
    // 峰位与峰值(1.0)不变；过渡段 ≈1.3px（smoothstep 两端一阶导为 0）⇒ 弧线上出现 2~3 个过渡像素。
    // 【P44】支撑半宽改为【运行时 uniform】（edgeHairSupport，× hairScale）：
    //   默认开档 = 新档（更锋利，见 GlassParameters.HAIR_BAND_SUPPORT_MUL）；
    //   关闭档 = HAIR_BAND_SUPPORT_MUL_LEGACY（5.0 = 改动前的值）⇒ 逐像素回旧观感；
    //   未设置(=0，如面板/桥路径) 也兜底到旧值 ⇒ 与改动前逐像素一致 ✓（NA 值同样走兜底：比较为 false）。
__LG_TRI_INSET__
    float lgHairSupport = (edgeHairSupport > 0.05) ? edgeHairSupport : ${f(GlassParameters.HAIR_BAND_SUPPORT_MUL_LEGACY)};
    float hairT = clamp(1.0 - abs(sd + lgTriHairInset + hairScale * 0.5) / (lgHairSupport * hairScale), 0.0, 1.0);
    float hairNew = hairT * hairT * (3.0 - 2.0 * hairT);
    float hairOld = clamp(1.0 - abs(sd + lgTriHairInset + hairScale * 0.5) / (1.0 * hairScale), 0.0, 1.0);
    float hair = mix(hairOld, hairNew, lgCardOn);
""".replace("__LG_TRI_INSET__", inset.trimEnd()) else """
__LG_TRI_INSET__
    float hair = clamp(1.0 - abs(sd + lgTriHairInset + hairScale * 0.5) / (1.0 * hairScale), 0.0, 1.0);
""".replace("__LG_TRI_INSET__", inset.trimEnd())
    }

    /** MAIN 里的发丝带占位符（由 [source] 按 [GlassParameters.HAIR_BAND_EDGE_AA] 替换）。 */
    private const val HAIR_BAND_TOKEN = "__LG_HAIR_BAND__"

    /**
     * 【直边→圆角切点接缝 · 同源法线】法线场的来源半径（AGSL 片段；由 [source] 替换 MAIN 里的
     * [GRAD_RADIUS_TOKEN] 占位符）。参数与机理见 [DebugSwitches.shapeNormalSameSource]。
     *
     * 关（默认）= 与修改前【逐字符等价】的旧行（法线来自 radius×1.5 的近似场）；
     * 开 = 法线与可见边界同源（同一个 radius ⇒ 同一套几何）。
     *
     * 实测（numpy 复算同一份 sdContinuousRect + 可见边界为零集）：
     *   切点处两法线夹角 18.3°、对角 45° 处 0°、直边 165px 外 0° ⇒ 偏差恰好集中在【直边→圆角切点】，
     *   而高光亮度 ∝ |dot(N, -L)|^1.2 ⇒ 亮度零点的落点被挪走（TL 角实测 deg≈33° ≠ 几何 45°）✓
     */
    private val GRAD_RADIUS_CODE: String
        get() = if (DebugSwitches.shapeNormalSameSource) {
            "    // 【同源法线·ON】法线用【与可见边界同一个半径】的场 ⇒ 切点处法线与直边自然衔接（C1）\n" +
                "    float gradRadius = radius;\n"
        } else {
            "    float gradRadius = min(radius * 1.5, min(shapeHalf.x, shapeHalf.y));\n"
        }

    /**
     * MAIN 里的法线半径占位符（整行匹配，**含行尾换行** —— 关档替换后 MAIN 与修改前逐字符相同）。
     */
    private const val GRAD_RADIUS_TOKEN = "__LG_GRAD_RADIUS__\n"

    /**
     * 【直边→圆角切点 · 溢出直线修复】角区早退条件（AGSL 片段；由 [source] 替换 MAIN 里的
     * [G2_TANGENT_TOKEN]）。参数与机理见 [DebugSwitches.g2TangentLeakFix]。
     *
     * 关（对照档）= 与修改前【逐字符等价】的旧条件行（u<=0/v<=0 也早退回 dBox）；
     * 开（默认）= 早退只保留「角区背后」（u>=1 / v>=1），边线外侧继续走角区公式。
     *
     * 实测（模拟器 emulator-5554，glassSize=0.5、p=0、r=72px、E=110px）：
     *   · 旧条件 ⇒ 场在【边线 Y=0】上不连续（节点局部 X=86 处：外侧 0.80 → 内侧 5.83，跳 5px）；
     *   · `gradShape`（数值梯度 eps=1px）跨这条线时法线整根翻转：(0,-0.98) → (0,+1.00)；
     *   · 折射位移 dispBase 随法线从 −62.7px 跳成 +61.0px（跳变 124px）⇒ 采样坐标被甩出屏幕
     *     ⇒ `dm2（仅折射）` 在 y=30、x∈[508,556] 出现整段 <100 的 1px 暗线（采样值 22 vs 背景 143）
     *     = 用户说的「直边段/圆角交点处溢出的直线」✓（与 numpy 逐像素复刻的预测一致）；
     *   · FINAL 同一处亮带被切成【双峰】（y=29 峰 179 / y=30 谷 156 / y=32 峰 250），
     *     而控制中心（面板 G2 路径 / 胶囊）同角度只有【单峰】⇒ 判据成立。
     *
     * 修复后：场在边线两侧连续 ⇒ 法线不折返 ⇒ 折射不跳变 ⇒ 双峰消失（谷值回到亮带包络）。
     */
    private val G2_TANGENT_CODE_FIX: String =
        "    // 【切线区溢出直线修复】早退只保留「角区背后」（u>=1 / v>=1）；边线外侧（u<=0 / v<=0）\n" +
            "    // 继续走角区公式 ⇒ 场在边线两侧连续（数值梯度法线不再跨线翻转）⇒ 折射位移不跳变。\n" +
            "    // 【只对卡片生效】tintModel>0.5 = 演示卡片；面板/胶囊（=0）取旧条件 ⇒ 逐像素不变 ✓；\n" +
            "    // 张力桥 radius=半高走上面的 E>0.95·min(halfSize) 回退分支 ⇒ 也逐像素不变 ✓。\n" +
            "    if (u >= 1.0 || v >= 1.0 || (tintModel <= 0.5 && (u <= 0.0 || v <= 0.0))) {\n"

    /** MAIN 里的角区早退条件占位符（整行 + 行尾换行；对照档替换后与修改前逐字符相同）。 */
    private const val G2_TANGENT_TOKEN = "__LG_G2_TANGENT_IF__\n"

    /** 角区早退条件的【对照档】（= 修改前的旧条件行，逐字符等价）。 */
    private const val G2_TANGENT_CODE_OLD =
        "    if (u <= 0.0 || u >= 1.0 || v <= 0.0 || v >= 1.0) {\n"

    /** 角区早退条件片段（按 [DebugSwitches.g2TangentLeakFix] 取修复档 / 对照档）。 */
    private fun g2TangentCode(leakFix: Boolean): String =
        if (leakFix) G2_TANGENT_CODE_FIX else G2_TANGENT_CODE_OLD

    /** MAIN 函数头（尺寸自适应片段的唯一插入锚点；AGSL 源码只按档位拼一次，随缓存复用）。 */
    private const val MAIN_HEAD = "half4 main(float2 coord) {\n"

    /**
     * 按档位取 AGSL 源码（含尺寸自适应片段）。
     *
     * @param tangentLeakFix 切线区溢出直线修复：true（默认）= 修复档；false = 与修改前【逐字符等价】的
     *        对照档（adapter 预生成两版 ⇒ `setSwitches g2TangentLeakFix 0|1` 运行时即刻 A/B，无需重启）。
     */
    fun source(quality: GlassQuality, tangentLeakFix: Boolean = true): String {
        val base = when (quality) {
            GlassQuality.PERFORMANCE -> HEADER + BLUR_TAPS_5 + MAIN
            GlassQuality.BALANCED -> HEADER + BLUR_TAPS_9 + MAIN
            GlassQuality.QUALITY -> HEADER + BLUR_TAPS_13 + MAIN
        }
        // 片段插在 main 开头（主函数内已可读 cardSize 等 uniform）。字符串只拼一次/档位。
        val src = base
            .replace(MAIN_HEAD, MAIN_HEAD + SIZE_ADAPTIVE_CODE)
            // 【锯齿修复③】发丝带剖面按开关替换（关 = 旧三角，逐字符等价 ⇒ 默认态可一键回退）
            .replace(HAIR_BAND_TOKEN, HAIR_BAND_CODE)
            // 【直边→圆角切点接缝】法线半径来源按开关替换（关 = 旧行，逐字符等价）
            .replace(GRAD_RADIUS_TOKEN, GRAD_RADIUS_CODE)
            // 【直边→圆角切点·溢出直线】角区早退条件按开关替换（对照档 = 旧行，逐字符等价）
            .replace(G2_TANGENT_TOKEN, g2TangentCode(tangentLeakFix))
            // 【P12·邻近流体融合】注入新增 AGSL 片段（shape2 + fuseK + 颈部/细丝并入联合场；
            //   融合 uniform 未设置 = 0 ⇒ 该段整体跳过，行为与改动前逐像素一致）
            .let { FusionShaders.injectInto(it) }
            // 【P07·融合 meld】注入归属划分片段（union 区只由更近的那块卡绘制 ⇒ 并集只画一次 =
            //   一次折射/一条轮廓/一条高光 ⇒ 无双边框无双亮带 ✓）。顺序在 FusionShaders 之后：
            //   划分作用在【已并入并集的 sd】之上。meldOwn 默认 0 ⇒ 未打开时逐像素等于改动前 ✓
            .let { MeldShaders.injectInto(it) }
        // 构建期留痕：这是判断「开关是否真的进了 AGSL 源码」的唯一可靠依据
        // （本片段在 shader 程序构建时读一次 ⇒ 翻开关必须重启应用才生效，见 shapeNormalSameSource 注释）
        // 构建期自检：角区早退条件占位符必须被替换掉（残留会让 AGSL 编译失败 = 玻璃整片消失 ✗）
        if (src.contains("__LG_G2_TANGENT_IF__")) {
            android.util.Log.e(
                "AIDebug",
                "GlassShaders.source: G2_TANGENT 占位符未被替换（AGSL 会编译失败）leakFix=$tangentLeakFix"
            )
        }
        android.util.Log.i(
            "AIDebug",
            "GlassShaders.source(${quality.name}): shapeNormalSameSource=" +
                "${DebugSwitches.shapeNormalSameSource} g2TangentLeakFix=$tangentLeakFix（srcLen=${src.length}）"
        )
        return src
    }

    // ------------------------------------------------------------------
    // 公共部分：uniform 声明 + SDF 工具 + 安全采样
    // ------------------------------------------------------------------
    private const val HEADER = """
uniform shader backdrop;

// 注意：不得使用 resolution / time / frame / date 作为自定义 uniform 名
//（它们是 AGSL 内置 uniform，重复声明会导致 Shader 编译失败）
uniform float2 layerSize;
uniform float blurLean;   // 【P64】动画期模糊降档（0=原 13t 逐像素一致；1=跳过 2.0× 外环 4 抽头并按 1/0.86 重归一）
uniform float2 offset;
uniform float2 cardOrigin;
uniform float2 cardSize;
uniform float4 cornerRadii;
uniform int shapeType;
uniform float edgeZonePx;
uniform float backgroundTransmission;
uniform float materialOpacity;
uniform float tintOpacity;
uniform float blurRadius;
uniform float edgeBlurRadius;
uniform float refractionOffset;
uniform float refractionHeight;
uniform float dispersionOffset;
uniform float dispersionEdgeWidth;
uniform float dispersionStrength;
uniform float cornerDispersionGain;   // 【P03·四角色散】四角权重门控（0/未设置 ⇒ 权重=1.0 不影响；见色散段）
uniform float fresnelStrength;
uniform float edgeHighlightOpacity;
uniform float edgeHairSupport;   // 【P44】贴边发丝带支撑半宽倍率（× hairScale；0/未设置 ⇒ AGSL 兜底 = 旧默认 5.0）
uniform float edgeShadowOpacity;
uniform float localDimmingOpacity;
uniform float saturation;
uniform float pressProgress;
uniform float2 pressPosition;
uniform float2 dragVelocity;
uniform float2 stretchDirection;
uniform float deformationStrength;
uniform float animTime;
uniform float adaptiveLegibility;
uniform float4 labelRegion;
// ---- iOS 透镜模型（移植自 QWEA0/Liquid-Glass-Android, MIT）----
uniform float falloff;        // 折射剖面：>0 = 逆幂衰减指数（引力透镜），0 = 平方斜面
uniform float touchAmp;       // 触点局部液态凸起幅度（0..1）
uniform float rimSoft;        // 折射带内沿法线方向的柔化宽度（px）
uniform float lensProfile;    // 1 = iOS 透镜剖面（默认），0 = 原 circleMap 剖面
uniform float adaptiveTint;   // 1 = 逐像素自适应染色（移植），0 = 固定色调
uniform float tintModel;      // 1 = 有色介质吸收模型（卡片），0 = 加性填充（面板）
uniform float2 shapeInflate;  // 按压时轮廓膨胀（px）：只改形状，不缩放内部画面
uniform float hdrBoost;       // HDR 高光增益：>1 时高光可超过 SDR 白点（1.0）
uniform float hdrCeil;        // 输出亮度上限：控制中心 = 1.0（彻底关掉 HDR 观感），卡片 = 4.0
uniform float finalDesat;     // 输出端按比例去色：控制中心 = 0.6（偏灰的磨砂），卡片 = 0
uniform float panelRimDarken; // 白底勾边：>0 时在白底上画一圈浅灰细线（白色高光在白底上没对比度）
uniform int debugMode;
layout(color) uniform half4 tintColor;
layout(color) uniform half4 materialColor;

// 圆角矩形 SDF（源自 Backdrop 库 AGSL 源码，Apache-2.0）
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

// 透镜式曲线：边缘处变化快，向中心迅速衰减
float circleMap(float x) {
    return 1.0 - sqrt(max(0.0, 1.0 - x * x));
}

float2 safeNormalize(float2 v) {
    float len = length(v);
    if (len > 0.0001) return v / len;
    return float2(0.0);
}

// ---- 形状 SDF：0=圆角矩形 1=圆形 2=胶囊 3=椭圆 4=三角形 5=六边形 6=超椭圆 ----
float sdSegment(float2 p, float2 a, float2 b) {
    float2 pa = p - a;
    float2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
    return length(pa - ba * h);
}

// 正六边形顶点（与 Compose HexagonShape 同源：外接圆参数化 ⇒ 等边等角、内角恒 120°）
// R = 0.98 × min(高/2, 宽/√3)（宽 = √3R ≤ 卡片宽、高 = 2R ≤ 卡片高）
// 顶点 v_k = R·(sin60°k, −cos60°k)；AGSL y 向下，点顶朝上
float sdHexagonShape(float2 p, float2 halfSize) {
    // 六个顶点，与 Compose HexagonShape 完全一致（同一外接圆半径 R、同一组 60° 顶点角）
    float R = 0.98 * min(halfSize.y, halfSize.x * 1.15470054);   // halfSize.x·(2/√3) = 宽/√3
    float2 v0 = float2(0.0, -R);
    float2 v1 = float2(R * 0.8660254, -R * 0.5);
    float2 v2 = float2(R * 0.8660254, R * 0.5);
    float2 v3 = float2(0.0, R);
    float2 v4 = float2(-R * 0.8660254, R * 0.5);
    float2 v5 = float2(-R * 0.8660254, -R * 0.5);
    float d = sdSegment(p, v0, v1);
    d = min(d, sdSegment(p, v1, v2));
    d = min(d, sdSegment(p, v2, v3));
    d = min(d, sdSegment(p, v3, v4));
    d = min(d, sdSegment(p, v4, v5));
    d = min(d, sdSegment(p, v5, v0));
    // 【必须给符号】：上面这个"到六条边的最小距离"恒为非负，而着色器用 sd<0 判断内部
    //（insideMask = 1 - smoothstep(-aa, aa, sd)）→ 没有符号时六边形内部拿不到材质/折射，
    // 表现就是"六边形完全没有玻璃效果"（用户反馈）。
    //
    // ⚠️ 内外符号用【完全展开】的射线交叉测试：AGSL 不允许 '%' 运算符，也不能用
    //    `(i+1)%6` 循环取模 —— 曾因此着色器编译失败（error: 114: operator '%' is not allowed）
    //    导致应用启动即崩。
    float inside = 0.0;
    float dy_0 = v1.y - v0.y;
    if ((v0.y > p.y) != (v1.y > p.y)) {
        if (p.x < (v1.x - v0.x) * (p.y - v0.y) / dy_0 + v0.x) { inside = 1.0 - inside; }
    }
    float dy_1 = v2.y - v1.y;
    if ((v1.y > p.y) != (v2.y > p.y)) {
        if (p.x < (v2.x - v1.x) * (p.y - v1.y) / dy_1 + v1.x) { inside = 1.0 - inside; }
    }
    float dy_2 = v3.y - v2.y;
    if ((v2.y > p.y) != (v3.y > p.y)) {
        if (p.x < (v3.x - v2.x) * (p.y - v2.y) / dy_2 + v2.x) { inside = 1.0 - inside; }
    }
    float dy_3 = v4.y - v3.y;
    if ((v3.y > p.y) != (v4.y > p.y)) {
        if (p.x < (v4.x - v3.x) * (p.y - v3.y) / dy_3 + v3.x) { inside = 1.0 - inside; }
    }
    float dy_4 = v5.y - v4.y;
    if ((v4.y > p.y) != (v5.y > p.y)) {
        if (p.x < (v5.x - v4.x) * (p.y - v4.y) / dy_4 + v4.x) { inside = 1.0 - inside; }
    }
    float dy_5 = v0.y - v5.y;
    if ((v5.y > p.y) != (v0.y > p.y)) {
        if (p.x < (v0.x - v5.x) * (p.y - v5.y) / dy_5 + v5.x) { inside = 1.0 - inside; }
    }
    if (inside > 0.5) {
        return -d;
    }
    return d;
}

// 超椭圆 n=4：f(p)=|x/a|^4+|y/b|^4-1 的近似符号距离
//（法向梯度归一后按 |∇f|≈2/短边 折算 px，内部为负）
float sdSuperellipse(float2 p, float2 halfSize) {
    float ax = max(halfSize.x, 0.5);
    float ay = max(halfSize.y, 0.5);
    float2 q = abs(p) / float2(ax, ay);
    float f = pow(q.x, 4.0) + pow(q.y, 4.0) - 1.0;
    // 一阶距离：f / |∇f|。旧的 f * min(ax,ay) * 0.5 是粗略缩放，
    // 各方向梯度差好几倍 → 贴边高光带忽粗忽细、四段"直边"几乎看不到亮线。
    float gx = 4.0 * pow(q.x, 3.0) / ax;
    float gy = 4.0 * pow(q.y, 3.0) / ay;
    float g = max(length(float2(gx, gy)), 0.0001);
    return f / g;
}

// 三角形顶点：上窄下宽等腰三角。注意 AGSL 坐标为 y-down（视觉上方 = y 负），
// 顶点必须与 Compose TriangleShape 裁剪一致（顶 0.14h，底 0.92h，宽 ±0.84w）
float sdTriangleShape(float2 p, float2 halfSize) {
    float2 p0 = float2(0.0, -halfSize.y * 0.72);
    float2 p1 = float2(-halfSize.x * 0.84, halfSize.y * 0.84);
    float2 p2 = float2(halfSize.x * 0.84, halfSize.y * 0.84);
    float2 e0 = p1 - p0;
    float2 e1 = p2 - p1;
    float2 e2 = p0 - p2;
    float2 v0 = p - p0;
    float2 v1 = p - p1;
    float2 v2 = p - p2;
    float2 pq0 = v0 - e0 * clamp(dot(v0, e0) / max(dot(e0, e0), 0.0001), 0.0, 1.0);
    float2 pq1 = v1 - e1 * clamp(dot(v1, e1) / max(dot(e1, e1), 0.0001), 0.0, 1.0);
    float2 pq2 = v2 - e2 * clamp(dot(v2, e2) / max(dot(e2, e2), 0.0001), 0.0, 1.0);
    float s = sign(e0.x * e2.y - e0.y * e2.x);
    float2 d = min(min(float2(dot(pq0, pq0), s * (v0.x * e0.y - v0.y * e0.x)),
                       float2(dot(pq1, pq1), s * (v1.x * e1.y - v1.y * e1.x))),
                       float2(dot(pq2, pq2), s * (v2.x * e2.y - v2.y * e2.x)));
    return -sqrt(d.x) * sign(d.y);
}

// G2 连续圆角矩形（与 Compose 侧 G2RoundedShape 同一套几何）：
// 角部沿两边各延伸 E = 1.5286651 * r（对应 Kyant0 G2ContinuityProfile.RoundedRectangle 的
// extendedFraction = 0.5286651），角部曲线为超椭圆 u^n + v^n = 1（n = 3，连续曲率→无折角突变）。
// E 过大（胶囊/手柄这类 r≈半高）时退回普通圆角，避免把胶囊压成方角。
// G2 连续圆角函数已移除：其角部符号反了（超椭圆方向朝外 → 会变成凹口），
// 且 Compose 侧路径坐标系错误（以原点为中心 → 裁剪只覆盖左半）。待用正确的
// Bézier 过渡段（上游 G2ContinuityProfile: extendedFraction=0.5286651）重做后再接入。

// G2 连续圆角矩形（与 Compose 侧 G2RoundedShape 同一套归一化定义，铁律：可见边界与 SDF 同源）。
// 角部区间沿两边各延伸 E = 1.5286651 * R（对齐上游 G2ContinuityProfile.RoundedRectangle 的
// extendedFraction = 0.5286651）；角部曲线为超椭圆 u^n + v^n = 1（n = 3），
// n > 2 时该曲线在直边交接处曲率为 0 → 与直边天然 C2 连续（不再有"折角突变"）。
// 归一化：u = (-q.x)/E（0 在右边线、1 在角方块内界），v = (-q.y)/E（0 在上边线）。
// 角方块外一律走圆角矩形距离；R≈半高（胶囊/手柄）或 E 过大时回退普通圆角，避免把胶囊压方。
float sdContinuousRect(float2 p, float2 halfSize, float radius) {
    float r = max(radius, 0.001);
    float E = 1.5286651 * r;
    if (E > min(halfSize.x, halfSize.y) * 0.95) {
        return sdRoundedRect(p, halfSize, r);      // 胶囊/手柄回退
    }
    float dBox = sdRoundedRect(p, halfSize, r);    // 基准：连续无折线
    float2 q = abs(p) - halfSize;
    float u = (-q.x) / E;                          // u=1 在角区与直边交界，u=0 在边线
    float v = (-q.y) / E;
__LG_G2_TANGENT_IF__
        return dBox;
    }
    float n = 3.0;
    float a = 1.0 - u;
    float b = 1.0 - v;
    // 角部项：内部为负（(1-u)^n+(1-v)^n <= 1 才算形状内部）
    float dCut = (pow(a, n) + pow(b, n) - 1.0) * (E / n);
    // 关键：权重在【角区边界】处严格为 0 → 返回值恒等于 dBox，与区外分支无缝衔接。
    // 之前直接用 max(dBox, dCut) 会在交界处跳变（实测仍留 4 条细线 ✗）。
    float w = smoothstep(0.0, 0.5, min(1.0 - u, 1.0 - v));
    return mix(dBox, max(dBox, dCut), w);
}

float sdShape(float2 p, float2 halfSize, float radius, int shapeType) {
    if (shapeType == 1) {
        // 圆形
        return length(p) - min(halfSize.x, halfSize.y);
    } else if (shapeType == 2) {
        // 胶囊：圆角矩形 + 全圆角
        return sdRoundedRect(p, halfSize, min(halfSize.x, halfSize.y));
    } else if (shapeType == 3) {
        // 椭圆：归一化圆
        return (length(p / max(halfSize, float2(0.001))) - 1.0) * min(halfSize.x, halfSize.y);
    } else if (shapeType == 4) {
        // 三角形（圆角化）
        return sdTriangleShape(p, halfSize) - radius * 0.8;
    } else if (shapeType == 5) {
        // 六边形（点顶朝上）
        return sdHexagonShape(p, halfSize);
    } else if (shapeType == 6) {
        // 超椭圆 n=4
        return sdSuperellipse(p, halfSize);
    } else {
        // 圆角矩形：G2 连续圆角
        return sdContinuousRect(p, halfSize, radius);
    }
}

float2 gradShape(float2 p, float2 halfSize, float radius, int shapeType) {
    if (shapeType == 1) {
        return safeNormalize(p);
    } else if (shapeType == 2) {
        return gradSdRoundedRect(p, halfSize, min(halfSize.x, halfSize.y));
    } else if (shapeType == 3) {
        float2 h2 = max(halfSize * halfSize, float2(0.001));
        return safeNormalize(p / h2);
    } else if (shapeType == 4) {
        // 三角形法线：数值梯度（3 次 SDF 调用）
        float eps = 1.0;
        float d = sdTriangleShape(p, halfSize);
        float dx = d - sdTriangleShape(p - float2(eps, 0.0), halfSize);
        float dy = d - sdTriangleShape(p - float2(0.0, eps), halfSize);
        return safeNormalize(float2(dx, dy));
    } else if (shapeType == 5) {
        // 六边形法线：数值梯度
        float eps = 1.0;
        float d = sdHexagonShape(p, halfSize);
        float dx = d - sdHexagonShape(p - float2(eps, 0.0), halfSize);
        float dy = d - sdHexagonShape(p - float2(0.0, eps), halfSize);
        return safeNormalize(float2(dx, dy));
    } else if (shapeType == 6) {
        // 超椭圆法线：隐式函数解析梯度 ∇(|x/a|^4 + |y/b|^4)
        float ax = max(halfSize.x, 0.5);
        float ay = max(halfSize.y, 0.5);
        float ax4 = ax * ax * ax * ax;
        float ay4 = ay * ay * ay * ay;
        float2 g = float2(4.0 * p.x * p.x * p.x / ax4, 4.0 * p.y * p.y * p.y / ay4);
        return safeNormalize(g);
    } else {
        // G2 连续圆角：数值梯度，保证法线与可见边界同源
        float eps = 1.0;
        float d0 = sdContinuousRect(p, halfSize, radius);
        float dx = d0 - sdContinuousRect(p - float2(eps, 0.0), halfSize, radius);
        float dy = d0 - sdContinuousRect(p - float2(0.0, eps), halfSize, radius);
        return safeNormalize(float2(dx, dy));
    }
}

// ---- 安全采样：clamp 到输入缓冲内（半像素边界防黑边/重复边）----
half4 safeSample(float2 coordinate) {
    float2 safeCoordinate = clamp(coordinate, float2(0.5, 0.5), layerSize - float2(0.5, 0.5));
    return backdrop.eval(safeCoordinate);
}

// ---- 玻璃内部遮罩：sd < 0 为内部 ----
float computeInsideMask(float signedDistance, float antialiasWidthPx) {
    float aa = max(antialiasWidthPx, 0.5);
    return 1.0 - smoothstep(-aa, aa, signedDistance);
}

// ---- 边缘窄带：玻璃内部到边缘的距离场（边缘≈1，向中心衰减到 0）----
float computeEdgeBand(float signedDistance, float edgeWidthPx, float insideMask) {
    float distanceInside = max(-signedDistance, 0.0);
    float width = max(edgeWidthPx, 0.5);
    float edge = 1.0 - smoothstep(0.5, width + 0.5, distanceInside);
    return edge * insideMask;
}

"""

    // ------------------------------------------------------------------
    // 各档位【无色散】模糊：所有 tap 的 R/G/B 使用完全相同的采样坐标；
    // 权重归一化（和 = 1.0），对角 tap 按 0.7071 缩放形成圆盘采样
    // ------------------------------------------------------------------
    private const val BLUR_TAPS_5 = """
half4 blurAchromatic5(float2 refractedCoord, float radiusPx) {
    // 逐像素微抖动（消网格摩尔纹）：只抖"采样位置"，不动整盘旋转 ✗ →
    // 规则环形采样在 30dp 大半径下会与大纹理形成网格/摩尔纹 ✗，
    // 亚抽头级抖动即可打散，且不会产生可见颗粒 ✓
    {
        float jx = fract(sin(dot(refractedCoord, float2(41.7, 289.1))) * 43758.5453) - 0.5;
        float jy = fract(sin(dot(refractedCoord, float2(269.5, 183.3))) * 43758.5453) - 0.5;
        refractedCoord += float2(jx, jy) * max(radiusPx, 0.0) * 0.14;
    }
    float radius = max(radiusPx, 0.0);
    // 逐像素随机旋转采样十字 + 半径微抖：固定排列的采样点在大半径下会呈现
    // 环状/十字状伪影（重影被复制成圈，观感像"散光"）。旋转把结构打散成
    // 细腻静态颗粒（磨砂玻璃质感）。R/G/B 坐标完全一致（无色散），权重归一化。
    // 修复①：逐像素随机 → 4×4 像素块低频随机（保留抗"甜甜圈"的旋转，消除逐像素颗粒/微锯齿 ✗）
    // 固定黄金角旋转：不再做逐像素/分块随机 —— 随机化会在模糊里留下 4px 块状不均 ✗
    //（用户反馈"模糊不干净"）。固定角度 + 足够抽头即可消除"甜甜圈"，且整片均匀 ✓
    float rnd = 0.61803399;
    float rnd2 = fract(rnd * 91.17);
    float ang = rnd * 1.5707963;
    float2 c = float2(cos(ang), sin(ang));
    float2 x = c * (radius * (0.86 + 0.28 * rnd2));
    float2 y = float2(-c.y, c.x) * (radius * (0.86 + 0.28 * rnd2));
    half4 result = safeSample(refractedCoord) * 0.50;
    result += safeSample(refractedCoord + x) * 0.125;
    result += safeSample(refractedCoord - x) * 0.125;
    result += safeSample(refractedCoord + y) * 0.125;
    result += safeSample(refractedCoord - y) * 0.125;
    return result;
}

// 统一模糊入口（main 只调用 applyBlur，按档位编译对应实现）
half4 applyBlur(float2 refractedCoord, float radiusPx) {
    return blurAchromatic5(refractedCoord, radiusPx);
}

"""

    private const val BLUR_TAPS_9 = """
half4 blurAchromatic9(float2 refractedCoord, float radiusPx) {
    // 逐像素微抖动（消网格摩尔纹）：只抖"采样位置"，不动整盘旋转 ✗ →
    // 规则环形采样在 30dp 大半径下会与大纹理形成网格/摩尔纹 ✗，
    // 亚抽头级抖动即可打散，且不会产生可见颗粒 ✓
    {
        float jx = fract(sin(dot(refractedCoord, float2(41.7, 289.1))) * 43758.5453) - 0.5;
        float jy = fract(sin(dot(refractedCoord, float2(269.5, 183.3))) * 43758.5453) - 0.5;
        refractedCoord += float2(jx, jy) * max(radiusPx, 0.0) * 0.14;
    }
    float radius = max(radiusPx, 0.0);
    // 逐像素随机旋转整盘采样（内环 0.7071、外环 1.0 同步转）+ 半径微抖：
    // 消除固定环形排列在大半径下的"甜甜圈"伪影（散光感来源）。
    // 修复①：逐像素随机 → 4×4 像素块低频随机（保留抗"甜甜圈"的旋转，消除逐像素颗粒/微锯齿 ✗）
    // 固定黄金角旋转：不再做逐像素/分块随机 —— 随机化会在模糊里留下 4px 块状不均 ✗
    //（用户反馈"模糊不干净"）。固定角度 + 足够抽头即可消除"甜甜圈"，且整片均匀 ✓
    float rnd = 0.61803399;
    float rnd2 = fract(rnd * 91.17);
    float ang = rnd * 6.2831853;
    float2 c = float2(cos(ang), sin(ang));
    float2 s = float2(-c.y, c.x);
    radius *= 0.90 + 0.20 * rnd2;
    float2 x = c * radius;
    float2 y = s * radius;
    float2 diagonalA = (c + s) * (radius * 0.70710678);
    float2 diagonalB = (c - s) * (radius * 0.70710678);
    half4 result = safeSample(refractedCoord) * 0.24;
    result += safeSample(refractedCoord + x) * 0.13;
    result += safeSample(refractedCoord - x) * 0.13;
    result += safeSample(refractedCoord + y) * 0.13;
    result += safeSample(refractedCoord - y) * 0.13;
    result += safeSample(refractedCoord + diagonalA) * 0.06;
    result += safeSample(refractedCoord - diagonalA) * 0.06;
    result += safeSample(refractedCoord + diagonalB) * 0.06;
    result += safeSample(refractedCoord - diagonalB) * 0.06;
    return result;
}

// 统一模糊入口（main 只调用 applyBlur，按档位编译对应实现）
half4 applyBlur(float2 refractedCoord, float radiusPx) {
    return blurAchromatic9(refractedCoord, radiusPx);
}

"""

    private const val BLUR_TAPS_13 = """
half4 blurAchromatic13(float2 refractedCoord, float radiusPx) {
    // 逐像素微抖动（消网格摩尔纹）：只抖"采样位置"，不动整盘旋转 ✗ →
    // 规则环形采样在 30dp 大半径下会与大纹理形成网格/摩尔纹 ✗，
    // 亚抽头级抖动即可打散，且不会产生可见颗粒 ✓
    {
        float jx = fract(sin(dot(refractedCoord, float2(41.7, 289.1))) * 43758.5453) - 0.5;
        float jy = fract(sin(dot(refractedCoord, float2(269.5, 183.3))) * 43758.5453) - 0.5;
        refractedCoord += float2(jx, jy) * max(radiusPx, 0.0) * 0.14;
    }
    float radius = max(radiusPx, 0.0);
    // 同 9t：整盘随机旋转 + 半径微抖，内外双环一起转，消除环状伪影。
    // 修复①：逐像素随机 → 4×4 像素块低频随机（保留抗"甜甜圈"的旋转，消除逐像素颗粒/微锯齿 ✗）
    // 固定黄金角旋转：不再做逐像素/分块随机 —— 随机化会在模糊里留下 4px 块状不均 ✗
    //（用户反馈"模糊不干净"）。固定角度 + 足够抽头即可消除"甜甜圈"，且整片均匀 ✓
    float rnd = 0.61803399;
    float rnd2 = fract(rnd * 91.17);
    float ang = rnd * 6.2831853;
    float2 c = float2(cos(ang), sin(ang));
    float2 s = float2(-c.y, c.x);
    radius *= 0.90 + 0.20 * rnd2;
    float2 x = c * radius;
    float2 y = s * radius;
    float2 diagonalA = (c + s) * (radius * 0.70710678);
    float2 diagonalB = (c - s) * (radius * 0.70710678);
    half4 result = safeSample(refractedCoord) * 0.30;
    result += safeSample(refractedCoord + x) * 0.10;
    result += safeSample(refractedCoord - x) * 0.10;
    result += safeSample(refractedCoord + y) * 0.10;
    result += safeSample(refractedCoord - y) * 0.10;
    result += safeSample(refractedCoord + diagonalA) * 0.04;
    result += safeSample(refractedCoord - diagonalA) * 0.04;
    result += safeSample(refractedCoord + diagonalB) * 0.04;
    result += safeSample(refractedCoord - diagonalB) * 0.04;
    // 【P64·动画期降档】blurLean=1（仅面板展开/收起动画期）跳过 2.0× 外环 4 抽头（13→9）并按 1/0.86 重归一
    //   （内环权重 0.30+0.10×4+0.04×4=0.86 ⇒ ×1.1627907 后权重和仍=1.0，亮度/对比度口径不变 ✓）；
    //   blurLean=0（默认 / 静止态 / 未设）走 else = 与改动前逐像素一致 ✓。回退：setSwitches animBlurLean 0。
    if (blurLean > 0.5) {
        result *= 1.1627907;
    } else {
        result += safeSample(refractedCoord + x * 2.0) * 0.035;
        result += safeSample(refractedCoord - x * 2.0) * 0.035;
        result += safeSample(refractedCoord + y * 2.0) * 0.035;
        result += safeSample(refractedCoord - y * 2.0) * 0.035;
    }
    return result;
}

// 统一模糊入口（main 只调用 applyBlur，按档位编译对应实现）
half4 applyBlur(float2 refractedCoord, float radiusPx) {
    return blurAchromatic13(refractedCoord, radiusPx);
}

"""

    // ------------------------------------------------------------------
    // 主函数：Refraction(Golden) → Achromatic Blur → Edge Dispersion → 合成
    // ------------------------------------------------------------------
    private const val MAIN = """
half4 main(float2 coord) {
    // ==================== 折射（Golden Reference，冻结） ====================
    float2 local = coord + offset;
    float2 halfSize = cardSize * 0.5;
    // 按压膨胀：把轮廓几何整体外扩（x/y 可不同），采样仍按屏幕坐标 1:1 ——
    // 于是"手指放上去只改变玻璃的形状"，玻璃内部看到的画面不会被放大
    float2 shapeHalf = max(halfSize + shapeInflate, float2(1.0));
    float2 centered = local - halfSize;
    float radius = radiusAt(local, cornerRadii);
__LG_GRAD_RADIUS__
    float sd = sdShape(centered, shapeHalf, radius, shapeType);

    // 覆盖率早退（v1.23.1）：形状外直接丢弃，可见边界由 SDF 自身给出（1.5px 羽化）。
    // 之前可见边界完全依赖外层容器的 clip 硬切 → 贴边高光/外辉光被"简单粗暴"切断
    //（用户反馈控制中心边缘高光被裁）。现在玻璃自己决定边界，容器 clip 只是兜底。
    float coverage = clamp(0.5 - sd / lgFeather, 0.0, 1.0);   // 羽化随尺寸缩放（lgFeather，2~5px）：大尺寸恒为 5px ✓   // 修复③：羽化 1.5px → 2.5px（边缘过渡更柔，压微锯齿）
    half4 directBg = half4(0.0);   // 修复⑤：捕获原背景色，供边界 alpha 混合使用
    if (coverage <= 0.004) {
        return directBg;
    }

    float edgeMask = 1.0 - smoothstep(0.0, lgEdgeZone, -sd);   // 边缘作用带随尺寸缩放（修：小尺寸下带子宽于形状 → 自交）
    float2 normal = gradShape(centered, shapeHalf, gradRadius, shapeType);
    float2 depthDir = safeNormalize(centered);
    float2 grad = safeNormalize(normal + depthDir * 0.45);   // 【P0】厚度混合 0.25→0.45（上游 depthEffect 用满 1.0；取中间偏强 ✓）

    // 边缘强、中心弱的折射位移（唯一 refractedCoord）
    float d = circleMap(1.0 - clamp(-sd / max(lgRefrHeight, 1.0), 0.0, 1.0));
    float2 refractedGolden = coord + grad * d * lgRefrOffset;

    // ============ iOS 透镜折射剖面（移植） ============
    // 移植自 QWEA0/Liquid-Glass-Android (MIT, Copyright (c) 2025-2026 pandadog)
    // 的 GlassLensRenderer.LENS_AGSL：厚度剖面 thickness = 1（内部平坦）→ 0（贴边），
    // 位移比例 slope 走逆幂衰减（引力透镜）——越贴边越剧烈，绝大部分弯折压在最外
    // 几个像素，内侧只留一段缓慢回落的轻微放大尾巴；沿外法线【向内采样】，
    // 于是边缘呈现为"内侧背景的压缩镜像"，这正是 iOS 的观感。
    float lensThickness = clamp(-sd / max(lgEdgeZone, 1.0), 0.0, 1.0);
    float lensSlope;
    if (falloff > 0.001) {
        float lensG = pow(5.0, -falloff);
        lensSlope = (pow(1.0 + 4.0 * lensThickness, -falloff) - lensG) / (1.0 - lensG);
    } else {
        float lensEdge = 1.0 - lensThickness;
        lensSlope = lensEdge * lensEdge;
    }
    float2 lensNormal = safeNormalize(normal);
    // ---- iOS 底部透镜（用户要求：左半往右、右半往左）----
    // 前两版都不对：v1.78 用方向混合 → 中线符号翻转产生折痕；v1.79 用线性位移混合
    // 仍留下可辨的分界。现在改用【以中线为轴的横向压缩】这一线性映射：
    //   x' = centerX + (x - centerX) * squeeze,  squeeze 随"底部权重 × 贴边程度"平滑变化
    // 这是一个处处光滑（C∞）的映射，结构上不可能出现分界线；左半因 (x-centerX)<0 被推向
    // 中线右侧、右半被推向左侧 —— 正是 iOS 底边那种"向中间收"的观感。顶部 squeeze=1，不受影响。
    float bottomW = smoothstep(0.05, 0.85, centered.y / max(shapeHalf.y, 1.0));
    // 【对齐上游·修正版】折射位移 = 圆弧映射，但作用带必须用上游的
    // refractionHeight = 24dp ≈ 67px ✓ —— 我上一版误用了 edgeZonePx（≈140px 的雾化带 ✗）
    // 且把法线符号翻了一次 ✗（方向反）→ 折射显得"诡异" ✓
    float refrHeight = max(lgEdgeZone * 0.48, 1.0);                 // ≈24dp（上游值；shader 内无 densityScale ✗，用 px 变量等效换算 ✓；随尺寸缩放）
    float tEdge = clamp(1.0 + sd / refrHeight, 0.0, 1.0);   // 1=贴边, 0=内侧（原来 -sd 方向反了：内部吃满位移 → 大面积扭曲/破洞）                  // 0=带外, 1=贴边
    float arcMap = 1.0 - sqrt(max(1.0 - tEdge * tEdge, 0.0));         // 单调圆弧（无拉丝 ✓）
    float2 dispBase = lensNormal * (arcMap * lgRefrOffset * (1.0 + 0.25 * pressProgress));
    float squeeze = 1.0;   // 【分支修复①】去掉底部横向压缩(原 0.16*bottomW*lensSlope)：它把底部内容压成糊状色块，置 1.0 = 与其他三边同构
    // 【缺陷修复】原先这里只用了 squeeze 的 X、丢掉了法线的 X 分量 ✗ →
    // 左右两侧（法线水平）与四个角完全看不到折射 ✗（用户反馈"折射仅发生在上下"）。
    // 现在：法线的完整位移回来 ✓ + 底部横向压缩作为【叠加项】保留 ✓
    float2 refractedLens = float2(
        (halfSize.x + (coord.x - halfSize.x) * squeeze) - dispBase.x,
        coord.y - dispBase.y
    );

    // 触点局部液态凸起（移植）：手指下方局部放大，采样向触点收缩
    if (touchAmp > 0.001) {
        float2 tp = local - pressPosition;
        float tr = length(tp);
        if (tr > 1.0) {
            float tSigma = max(max(halfSize.x, halfSize.y), 1.0);
            float tBump = touchAmp * exp(-(tr * tr) / (tSigma * tSigma * 0.30));
            refractedLens -= (tp / tr) * (tBump * lgRefrOffset * 0.5);
        }
    }

    // 剖面选择：lensProfile = 1 → iOS 透镜（默认），0 → 原 circleMap（Golden 对照）
    float2 refracted = (lensProfile > 0.5) ? refractedLens : refractedGolden;

    // 按压形变：局部凹陷已大幅减弱（整体放大由 Kotlin graphicsLayer 承担），
    // 此处仅保留极轻微触点凹陷细节
    if (pressProgress > 0.001) {
        float2 toPress = pressPosition - local;
        float distP = length(toPress);
        float pressRadius = min(halfSize.x, halfSize.y) * 0.45;
        float core = exp(-(distP * distP) / (2.0 * pressRadius * pressRadius));
        float pressK = pressProgress * deformationStrength;
        refracted += safeNormalize(toPress + float2(0.001)) * core * pressK * 2.2;
    }

    // 拖动形变：凝胶拉伸（大幅降低——过大的背景位移会造成"内容不跟手"）
    float dragMag = clamp(length(dragVelocity) / 2400.0, 0.0, 1.0);
    // 方向修正（用户反馈"运动方向和预期不符"）：拖动时玻璃内的内容应【反向拖尾】
    // ——原实现沿拖动方向前推（内容跑在手指前面 ✗），现改为反向，符合惯性直觉 ✓。
    refracted -= stretchDirection * (dragMag * dragMag) * deformationStrength * 12.0;

    // 采样坐标安全限制（safeSample 内部还会再做半像素 clamp）
    refracted = clamp(refracted, float2(0.0), layerSize);

    // ============ 调试：BACKGROUND_ONLY（完全原始背景，无色散无折射） ============
    if (debugMode == 1) {
        return backdrop.eval(coord);
    }

    // ============ 调试：REFRACTION_ONLY（Golden Reference 回归） ============
    if (debugMode == 2) {
        half4 raw = safeSample(refracted);
        return half4(raw.rgb * backgroundTransmission, 1.0);
    }

    // ==================== 无色散模糊（共享同一 refractedCoord） ====================
    // 中心低模糊、边缘稍高模糊：半径由 edgeMask 混合
    float blurR = mix(lgBlurRadius, lgEdgeBlurRadius, edgeMask);
    // 【原生化①】移除 shader 手写 N 抽头模糊 ── 背景已由平台 BlurEffect（硬件高斯）模糊过 ✓
    //   原实现再做一遍 = 双重模糊（评审：面板等效 ~42dp，与注释"替代手写模糊"不符 ✗）
    //   改为直接取中心采样：消除双重模糊 + 省 13 次 backdrop.eval/像素 ✓
    // 【修复·模糊丢失】原生化① 曾把手写模糊换成平台 BlurEffect 独占，但实测
    //   平台那层并未真正撑起模糊（真机上玻璃只剩半透明灰膜 ✗）→ 恢复手写模糊 ✓
    half4 blurred = applyBlur(refracted, blurR);

    // ============ 调试：ACHROMATIC_BLUR_ONLY（折射 + 无色散模糊） ============
    if (debugMode == 3) {
        return half4(blurred.rgb, 1.0);
    }

    // ============ 调试：BLUR_COMPARE（纯模糊对照：图形不变、无折射无高光） ============
    // 注意：必须用 applyBlur（各档位统一入口）——blurAchromatic5/9/13 只存在于对应档位源码段
    if (debugMode == 5) {
        half4 compare = applyBlur(coord, mix(lgBlurRadius, lgEdgeBlurRadius, edgeMask));
        return half4(compare.rgb, 1.0);
    }

    // ==================== 边缘色散（独立窄带 delta） ====================
    float insideMask = computeInsideMask(sd, 1.5);
    float edgeBand = computeEdgeBand(sd, lgDispWidth, insideMask);
    // 【P03·四角色散】四角权重 = |nx·ny| × 双轴 smoothstep（Apple/上游原理 dispersionIntensity ∝ (x·y)/(hw·hh)，见提交 455f465）：
    //   中线/中心/直边中段（|n|≤0.80）恒为 0 ✓、四角最大 ✓（历史档 ×3.2 会把权重漏到整条直边 ⇒ 按验收"直边≈0"收窄为 smoothstep）
    //   cornerDispersionGain=0（默认/面板/未设置）⇒ mix 退化为 1.0 ⇒ 本行逐值等于改动前 ✓
    float2 ccn = centered / shapeHalf;
    float cornerW = abs(ccn.x * ccn.y) * smoothstep(0.80, 1.0, abs(ccn.x)) * smoothstep(0.80, 1.0, abs(ccn.y));
    cornerW = mix(1.0, cornerW, clamp(cornerDispersionGain, 0.0, 1.0));
    float dispersionMask = edgeBand * clamp(dispersionStrength, 0.0, 1.0) * cornerW;

    // 主颜色：正确折射坐标的中心采样
    half4 refractedSample = safeSample(refracted);

    // 边缘柔化（移植）：折射带内沿法线方向抹匀（宽度随斜面深度增长），
    // 把"一条硬线"变成一段渐变；采样点仍钳在内容区内
    if (lgRimSoft > 0.01 && lensSlope > 0.001) {
        float2 sm = lensNormal * (lgRimSoft * lensSlope);
        half4 s1 = safeSample(refracted - sm);
        half4 s2 = safeSample(refracted + sm);
        refractedSample = half4(
            (refractedSample.r + s1.r + s2.r) / 3.0,
            (refractedSample.g + s1.g + s2.g) / 3.0,
            (refractedSample.b + s1.b + s2.b) / 3.0,
            refractedSample.a
        );
    }

    // 色散探针：RGB 三通道取不同位移 → 连续谱彩虹边；Alpha 始终取中心采样
    half3 chromaDelta = half3(0.0);
    if (dispersionMask > 0.001) {
        // 分裂量按 slope 缩放（移植）：中心 slope→0 时完全无色散，
        // 光谱边纹只落在贴边带内，且与折射位移同源同向
        float2 dispersionDir = safeNormalize(normal + float2(0.0001, 0.0001));
        float dispScale = clamp(0.25 + 1.75 * lensSlope, 0.0, 2.0);
        float2 dispVec = dispersionDir * (max(lgDispOffset, 0.0) * dispScale);
        half4 redSample = safeSample(refracted + dispVec);
        half4 greenSample = safeSample(refracted + dispVec * 0.45);
        half4 blueSample = safeSample(refracted - dispVec);
        half3 probe = half3(redSample.r, greenSample.g, blueSample.b);
        chromaDelta = probe - refractedSample.rgb;
    }

    // ============ 调试：DISPERSION_ONLY（放大 chromaDelta 用于观察） ============
    if (debugMode == 4) {
        return half4(abs(chromaDelta) * 8.0, 1.0);
    }

    // ==================== 最终合成 ====================
    // baseOptical = refractedSample ↔ blurred 混合（中心几乎不模糊，边缘强模糊）
    float blurMix = clamp(0.10 + 0.75 * edgeMask, 0.0, 1.0);
    half4 baseOptical = mix(refractedSample, blurred, blurMix);
    // 只加边缘 chromaDelta（×2.2 增强彩虹感），不做三通道重模糊
    half3 finalRgb = baseOptical.rgb + chromaDelta * half(dispersionMask) * 1.35;

    // ---- 透射 + 染色（v1.17.0：移植 iOS 材质模型）----
    half4 glass = half4(finalRgb, baseOptical.a);
    glass = glass * backgroundTransmission;
    if (tintModel > 0.5) {
        // 有色介质模型（移植自 QWEA0/Liquid-Glass-Android）：吸收（保留背景明暗层次与
        // 折射细节）+ 少量散射（暗背景下也看得出色相）。位置在光照之前——染色属于透射、
        // 镜面高光属于表面反射，不该被染色。
        float lumTint = dot(glass.rgb, half3(0.2126, 0.7152, 0.0722));
        half3 absorbed = glass.rgb * mix(half3(1.0), materialColor.rgb, 0.85);
        half3 scattered = materialColor.rgb * half(0.38 * (1.0 - lumTint));
        glass.rgb = mix(glass.rgb, clamp(absorbed + scattered, half3(0.0), half3(1.0)), half(materialOpacity));
    } else {
        // 加性材质填充：面板用它承载浅色文字的稳定暗底，不能换模型
        glass.rgb += materialColor.rgb * materialOpacity;
    }
    if (adaptiveTint > 0.5) {
        // 逐像素自适应染色（移植）：按局部亮度在提亮/压暗之间平滑过渡，
        // 玻璃跨明暗背景时不会整体翻转（"Regular" 材质观感）
        float lumAdapt = dot(glass.rgb, half3(0.2126, 0.7152, 0.0722));
        float e = smoothstep(0.35, 0.75, lumAdapt);
        glass.rgb = mix(glass.rgb, half3(1.0 - e), half(0.14 + 0.08 * e));
    } else {
        glass.rgb += tintColor.rgb * tintOpacity;
    }

    // ---- 局部暗化 + 内容感知可读性（Shader 内亮度判断，无像素回读）----
    float lum = dot(blurred.rgb, half3(0.299, 0.587, 0.114));
    float inLabel = 0.0;
    if (labelRegion.z > 0.0 && adaptiveLegibility > 0.5) {
        float lx = labelRegion.x;
        float ly = labelRegion.y;
        float lw = labelRegion.z;
        float lh = labelRegion.w;
        float feather = 8.0;
        inLabel = smoothstep(lx, lx + feather, local.x) * (1.0 - smoothstep(lx + lw - feather, lx + lw, local.x))
                * smoothstep(ly, ly + feather, local.y) * (1.0 - smoothstep(ly + lh - feather, ly + lh, local.y));
    }
    float darken = inLabel * smoothstep(0.55, 0.85, lum);
    float brighten = inLabel * (1.0 - smoothstep(0.05, 0.22, lum));
    float dim = localDimmingOpacity * (1.0 - edgeMask) * 0.5 + darken * 0.12 + brighten * 0.06;
    glass.rgb *= (1.0 - dim);
    glass.rgb += half3(brighten * 0.06);

    // ---- 边缘光照：iOS 双角度瓣模型（移植）----
    // 移植自 QWEA0/Liquid-Glass-Android 的 GlassLensRenderer.LENS_AGSL（MIT）：
    // 整圈亮线的明暗只由 dot(N, -L) 决定，没有与方向无关的常亮项——侧向
    // （法线垂直于光线处）归零，因此不会留下一圈固定描边；迎光侧与背光侧峰值
    // 相等（背光侧是透明介质的内壁反射），瓣宽 pow 4.5（离轴 30° 剩一半、45° 归零）；
    // 迎光侧另加一层向内的柔和辉光。因为完全由法线场驱动，任何形状的
    // 四个角、尖角、圆角都自动贴合可见边界。
    half3 rgb = glass.rgb;

    // 终端去色（原在输出端）：只洗掉底材/材质的偏色，让面板呈"偏灰磨砂"；
    // 必须放在高光叠加【之前】——否则会把高光里来自背景的颜色一起洗成死白，
    // 高光就与背景失去联动（用户反馈：左下/右上两处高光跟背景没关系）。
    float desatAmt = clamp(finalDesat, 0.0, 1.0);
    if (desatAmt > 0.0) {
        float fl = dot(rgb, half3(0.2126, 0.7152, 0.0722));
        rgb = mix(half3(fl), rgb, half(1.0 - desatAmt));
    }

    float lightAngle = -0.75 + cardOrigin.x * 0.00004 + cardOrigin.y * 0.00002;
    float2 lightDir = float2(cos(lightAngle), sin(lightAngle));
    float2 nrm = safeNormalize(normal);
    float facing = dot(nrm, -lightDir);
    // 【P0 采样上游】方向性高光：上游 Default = pow(|dot(法线, 光向)|, falloff)，官方基线 falloff=1
    //   我们原来是 pow(facing, 4.5) ✗ → 高光被压成两个集中亮点
    //   降到 1.2 之后：光沿边界铺成一道【平滑弧光】✓ = iOS 那种边缘弧光观感 ✓
    float lobeF = pow(max(facing, 0.0), 1.2);
    float lobeB = pow(max(-facing, 0.0), 1.2) * 1.05;   // 两侧 lobe 幅度拉平（上游用 abs()，本就对称 ✓）

    // 贴边发丝亮线（中心在边内约 1.5px、半宽 1.5px）+ 迎光侧内辉光
    float hairScale = lgHair;      // 贴边发丝亮线：随尺寸缩放（下限 1px，保证可见 + 自带 AA）
    float glowScale = lgGlow;      // 迎光侧内辉光：随尺寸缩放（下限 1.5px）
    float bandW = max(min(clamp(lgEdgeZone * 0.30, 3.0, 18.0), lgTriHiCeil), 1.0);   // 【棱镜高光宽】叠加「内切圆半径 × kHighlightFrac」上限（大尺寸下恒不生效 ⇒ 逐像素不变）
    float glowIn = clamp((-sd - hairScale) / glowScale, 0.0, 1.0);
    float glow = glowIn * pow(clamp(1.0 - (-sd - glowScale) / bandW, 0.0, 1.0), 1.5);
    // 【对齐上游】描边收窄：hairScale 减半 → 细而亮的边（原来偏宽，叠上暗带就发浑 ✗）
    // 【锯齿修复③】发丝带剖面：由 [HAIR_BAND_TOKEN] 替换（开 = Hann 平滑剖面 / 关 = 旧三角）
__LG_HAIR_BAND__
    // hdrBoost：窗口处于 HDR 色彩模式时把高光推过 SDR 白点，边缘会真的"发亮"；
    // 非 HDR 设备该值恒为 1.0，高光照旧（超过 1.0 的部分被显示端截到纯白，观感不变）。
    float spec = (hair * 0.52 * (lobeF + lobeB) + glow * 0.10 * lobeF)   // 0.70→0.52：指数降低后的亮度补偿 ✓
                 * edgeHighlightOpacity * 2.0
                 * (0.6 + 0.4 * clamp(fresnelStrength, 0.0, 2.0))
                 * max(hdrBoost, 1.0)   // 【审查修复①】兜底：漏传时按 1.0（原 0.0 会把高光整段乘没 ✗）
                 * (1.0 - 0.35 * pressProgress);
    // ---- 高光与背景互动（v1.20.2）----
    // 高光不再一律纯白：用边缘处的背景采样给它上色（暖背景出暖高光、冷背景出冷高光），
    // 并按背景亮度调节强度——亮背景（天空/雪）上收敛避免过曝成一片白，
    // 暗背景上更亮更"浮"，从而与下方画面产生互动。
    // ---- 高光与背景的联动（v1.63.0：三个驱动项，幅度做足）----
    // ① 亮度：亮背景（天空/雪）上高光收敛，暗背景上强烈"浮起"，动态范围 ≈ 8 倍
    // ② 饱和度：背景越浓，高光越带该颜色（暖背景出暖高光、冷背景出冷高光）
    // ③ 细节：背景未模糊采样与模糊采样之差 = 画面结构量；结构越丰富高光越"活"
    // 取色也几乎完全跟随背景（混入 85%），因此左下/右上两瓣光会随底色明显变化。
    float bgLum = dot(blurred.rgb, half3(0.2126, 0.7152, 0.0722));
    float bgMax = max(blurred.r, max(blurred.g, blurred.b));
    float bgMin = min(blurred.r, min(blurred.g, blurred.b));
    float bgSat = clamp((bgMax - bgMin) * 2.0, 0.0, 1.0);
    // 修复②：detail = "未模糊采样 − 模糊采样" 本质是逐像素高频量，乘进高光会让
    // 边缘发毛、出现微小锯齿 ✗。改为极低权重（仅保留一点点结构感），主联动交给
    // 低频的 bgLum / bgSat 两项。
    float detail = clamp(length(refractedSample.rgb - blurred.rgb) * 0.6, 0.0, 0.6);
    // 用户要求：高光映射背景色彩更明显（尤其底部两团原来偏中性白 ✗）
    // 背景占比 0.85→0.95、色彩增益 1.5→1.75、白点抬升 0.10→0.05 → 高光带更明显的背景色
    half3 specTint = mix(half3(1.0), blurred.rgb * half(1.75) + half(0.05), half(0.95));
    float bgFactor = mix(2.6, 0.32, smoothstep(0.10, 0.85, clamp(bgLum, 0.0, 1.0)));
    float liveFactor = bgFactor * (0.62 + 0.9 * bgSat) * (0.92 + 0.25 * detail);   // detail 权重 0.7→0.25
    rgb += specTint * half(spec * liveFactor);

    // 白底勾边（v1.27.1）：白色高光叠在白底上没有对比度，看起来"像白纸"。
    // 按形态补一圈浅灰细线（iOS 浅色模式的做法）。panelRimDarken 只在面板态>0，
    // 卡片与收起态为 0；且这一项不吃 hdrBoost，避免又被推过曝。
    // ---- 厚暗外圈（新分支）：沿边界向内一条有"厚度"的暗色 rim ----
    // panelRimDarken 在此分支复用为"rim 强度"，宽度取边缘带的一小段（约 4~6px）。
    // 与发丝高光叠加 → 参考图那种"高光 + 暗边 + 内侧倒角"的厚玻璃边缘。
    // 三层结构（对齐参考图）：最外一条【亮高光】→ 内侧【暗倒角带】→ 正常玻璃。
    // 之前暗带正好压在发丝高光上 → 亮峰被吃掉（剖面实测 184 的亮峰消失 ✗）。
    float rim = clamp(panelRimDarken, 0.0, 1.0);
    // 【脏边修复】作用带 3~9px → 1.5~3.5px：原来这圈把"发丝高光 + 暗倒角 +
    // 被大折射拉伸的彩色内容"叠在一起 → 2~5px 的彩色拉丝+灰雾 = 用户说的"脏" ✗。
    // 收窄到 1.5~3.5px 后，边缘是一条细而干净的边（亮高光 + 细暗线）✓
    // 【对齐上游】上游 Highlight = width 0.5dp + blurRadius = width/2 + falloff 2
    // → 描边极细且柔和；我们原来是 1.5~3.5px 的"暗带" ✗（= 用户说的"脏" ✓）
    float rimW = clamp(lgEdgeZone * 0.02, 1.0, 1.6);
    float rimBand = 1.0 - smoothstep(0.0, rimW * 2.0, -sd);   // 2× 宽度 = 半宽模糊语义 ✓
    // 暗带从高光内侧开始（扣掉发丝那一圈），保证高光不被压暗
    float bandInner = max(rimBand - hair * 1.2, 0.0);
    float rimAmt = rim * bandInner;
    rgb = mix(rgb, rgb * half(0.55), half(hair * rim * 0.35));       // 高光只轻微压暗
    // 【对齐上游】上游没有"厚暗外圈"这条（它用独立的 InnerShadow ✓）。
    // 我们保留极轻的一丝（0.62 → 0.88）只作体积感，不再形成可见暗带 ✓
    rgb = mix(rgb, rgb * half(0.88), half(rimAmt));

    // 极轻内暗边：iOS 模型本身没有内侧暗带，这里只保留可控的微弱分量
    float shadow = edgeMask * pow(max(-facing, 0.0), 2.0) * edgeShadowOpacity * 0.25;
    rgb *= (1.0 - shadow);

    // 饱和度：降饱和走线性；提饱和走 vibrancy 曲线（移植）——像素多提、高饱和像素少提、
    // 极亮像素保护，避免线性提饱和把浓色推过曝
    float luma = dot(rgb, half3(0.2126, 0.7152, 0.0722));
    if (saturation <= 1.0) {
        rgb = mix(half3(luma), rgb, saturation);
    } else {
        float satNow = max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
        float room = 1.0 - smoothstep(0.2, 0.85, satNow);
        float hl = 1.0 - smoothstep(0.75, 0.98, luma);
        float amount = 1.0 + (saturation - 1.0) * mix(0.3, 1.0, room * hl);
        rgb = clamp(mix(half3(luma), rgb, half(amount)), half3(0.0), half3(1.0));
    }

    // 输出亮度封顶：窗口一旦是 COLOR_MODE_HDR，>1.0 的部分会被真实显示得更亮。
    // 控制中心不希望有 HDR 味 → hdrCeil 传 1.0 把输出压回 SDR 白点；
    // 演示卡片保留 HDR → 传 4.0（等效不封顶）。
    rgb = min(rgb, half3(max(hdrCeil, 0.0)));

    // ---- 亚 LSB 抖动（消色带/消量化台阶）----
    // 30dp 强模糊会把背景压成极平缓的色块，而屏幕只有 8bit（1/255≈0.0039）→
    // 相邻色阶之间出现可见台阶（用户反馈"色带断层 + 方形小块"）。
    // 加入 ±0.5/255 的逐像素抖动，把硬台阶打散成肉眼不可见的过渡：
    // 幅度必须小于半个 LSB，否则会变成可见颗粒 ✗。
    float dither = fract(sin(dot(local, float2(12.9898, 78.233))) * 43758.5453) - 0.5;   // 用 local（AGSL 不允许 gl_FragCoord ✗）
    rgb += half3(dither * 0.00196);   // 0.00196 ≈ 0.5/255
    // 修复⑤：用覆盖率把玻璃色与原背景做 alpha 混合 —— 这才是抗锯齿。
    // 此前 coverage 仅用于早退判断 ✗ → 边界是硬阈值 → 逐像素方块台阶（8x 放大已证实 ✗）。
    // 说明：这里原来混合到 half4(0.0)（透明黑 ✗）是错的；探针（红色）证实
    // 卡片可见边界并不来自本段 coverage ✗ → 这里改为混合到已采样的背景色，行为正确且无害。
    // 【已移除】原想用 coverage 做边界 alpha 混合，但探针（红色）证实卡片可见边界
    // 不来自本段 coverage ✗，且该行引用了不存在的变量会触发 AGSL 首绘崩溃 ✗ → 删除后回到原状。
    // 【抗锯齿修复】输出 alpha 乘上覆盖率：边界处 alpha 由 1 平滑降到 0 = 真正的抗锯齿。
    // 此前 coverage 只用于早退判断 ✗，边界是 0/1 硬切（放大 8x 可见逐像素台阶）。
    return half4(rgb, clamp(glass.a, 0.0, 1.0) * clamp(coverage, 0.0, 1.0));

}
"""
}
