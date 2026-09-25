package com.example.liquidglass.glass

import com.example.liquidglass.debug.DebugSwitches

/**
 * 玻璃光学参数（不可变数据类）。
 *
 * 重要约定：
 * - 玻璃容器整体合成 Alpha 恒为 1.0，卡片前景文字/图标 Alpha 恒为 1.0；
 * - 透明度通过 Shader uniform 分别调节（背景透射率 / 材质填充 / 色调 / 模糊 /
 *   局部暗化 / 边缘高光 / 边缘暗边），绝不对整张卡片使用 Modifier.alpha(...)；
 * - 所有 dp 光学参数在进入 AGSL 前转换为 px，不得把 dp 直接当作 Shader 像素单位。
 *
 * glassTransparency 方向约定：
 *   t = 0 → 更接近磨砂、不透明；t = 1 → 更接近清透玻璃。
 */
data class GlassParameters(
    /** 当前预设（CUSTOM 表示用户独立调参状态）。 */
    val preset: GlassPreset,
    /** 宏观玻璃透明度（0=磨砂，1=清透）；实际取值落在 [TRANSPARENCY_RANGE] 窗口（0.48..0.94）内，窗口外的输入与端点同效。 */
    val glassTransparency: Float,
    /** 背景透射率：背景采样颜色的保留比例。 */
    val backgroundTransmission: Float,
    /** 材质填充不透明度（白色/中性填充，超透模式下必须极低）。 */
    val materialOpacity: Float,
    /** 色调不透明度（冷色环境色调）。 */
    val tintOpacity: Float,
    /** 中心模糊半径（dp）。 */
    val blurRadiusDp: Float,
    /** 边缘模糊半径（dp）。 */
    val edgeBlurRadiusDp: Float,
    /** 边缘折射采样偏移（dp）。 */
    val refractionOffsetDp: Float,
    /** 色散偏移（dp），必须克制（限幅 ≤1.0dp），不得变成 RGB 故障风格。 */
    val dispersionOffsetDp: Float,
    /** 色散作用带宽度（dp），窄带默认 10dp。 */
    val dispersionEdgeWidthDp: Float = 10f,
    /** 色散强度（0~1），默认 0.10~0.30。 */
    val dispersionStrength: Float = 0.15f,
    /** 菲涅尔强度。 */
    val fresnelStrength: Float,
    /** 边缘高光强度。 */
    val edgeHighlightOpacity: Float,
    /** 边缘暗边强度。 */
    val edgeShadowOpacity: Float,
    /** 局部暗化强度（0.00～0.12 之间平滑插值）。 */
    val localDimmingOpacity: Float,
    /** 饱和度（1.0 = 原始）。 */
    val saturation: Float,
    /** 凝胶形变强度（按压凹陷 + 拖动拉伸共用）。 */
    val deformationStrength: Float,
    /** 回弹弹簧刚度。 */
    val springStiffness: Float,
    /** 回弹弹簧阻尼比。 */
    val springDampingRatio: Float
) {

    /** 进入自定义模式：保留当前数值，仅标记预设为 CUSTOM。 */
    fun toCustom(): GlassParameters = copy(preset = GlassPreset.CUSTOM)

    /**
     * 通过宏观透明度 t 重新派生一组光学参数（保留预设身份）。
     * 用户拖动“玻璃透明度”滑块时调用，方向为：t 越大越清透。
     */
    fun withTransparency(t: Float): GlassParameters {
        val linked = mapping(t.coerceIn(0f, 1f))
        return copy(
            glassTransparency = t.coerceIn(0f, 1f),
            backgroundTransmission = linked.backgroundTransmission,
            materialOpacity = linked.materialOpacity,
            tintOpacity = linked.tintOpacity,
            blurRadiusDp = linked.blurRadiusDp,
            edgeBlurRadiusDp = linked.edgeBlurRadiusDp,
            refractionOffsetDp = linked.refractionOffsetDp,
            dispersionOffsetDp = linked.dispersionOffsetDp,
            dispersionEdgeWidthDp = linked.dispersionEdgeWidthDp,
            dispersionStrength = linked.dispersionStrength,
            fresnelStrength = linked.fresnelStrength,
            edgeHighlightOpacity = linked.edgeHighlightOpacity,
            edgeShadowOpacity = linked.edgeShadowOpacity,
            localDimmingOpacity = linked.localDimmingOpacity,
            saturation = linked.saturation
        )
    }

    /**
     * 【玻璃风格拉杆】拉杆位置 s(0..1) → 整组参数（用户需求：最左=通透、默认中位=均衡、最右=磨砂）。
     *
     * 分段线性：s=0→通透 t=0.94 / s=0.5→均衡 t=0.78 / s=1→磨砂 t=0.48（锚点值一律取自
     * GlassPreset.transparency 常量，不写魔数）；段内线性插值后复用既有 [mapping] 派生光学参数
     * （折射/菲涅耳/边缘高光/内暗边/饱和三档共用，一字不动）；形变强度与弹簧同规则分段线性
     * （1.0/0.85/0.6、420/380/320、0.72/0.78/0.86）。
     * 【逐字段等价判据】端点精确插值保证 withStylePosition(0f/0.5f/1f) ==
     * preset(通透/均衡/磨砂)（data class 逐字段相等，可机械验证）。
     * 防 NaN：s 非有限直接返回本实例（coerceIn 对 NaN 放行 —— 本工程已踩过 NaN 穿透事故）。
     */
    fun withStylePosition(s: Float): GlassParameters {
        if (!s.isFinite()) return this
        val p = s.coerceIn(0f, 1f)
        val clear = GlassPreset.IOS26_BETA1_ULTRA_CLEAR
        val balanced = GlassPreset.BALANCED
        val frosted = GlassPreset.FROSTED_ACCESSIBLE
        val t = styleValue(p, clear.transparency, balanced.transparency, frosted.transparency)
        val linked = mapping(t)
        return copy(
            preset = stylePresetOf(p),
            glassTransparency = t,
            backgroundTransmission = linked.backgroundTransmission,
            materialOpacity = linked.materialOpacity,
            tintOpacity = linked.tintOpacity,
            blurRadiusDp = linked.blurRadiusDp,
            edgeBlurRadiusDp = linked.edgeBlurRadiusDp,
            refractionOffsetDp = linked.refractionOffsetDp,
            dispersionOffsetDp = linked.dispersionOffsetDp,
            dispersionEdgeWidthDp = linked.dispersionEdgeWidthDp,
            dispersionStrength = linked.dispersionStrength,
            fresnelStrength = linked.fresnelStrength,
            edgeHighlightOpacity = linked.edgeHighlightOpacity,
            edgeShadowOpacity = linked.edgeShadowOpacity,
            localDimmingOpacity = linked.localDimmingOpacity,
            saturation = linked.saturation,
            deformationStrength = styleValue(p, clear.deformationStrength, balanced.deformationStrength, frosted.deformationStrength),
            springStiffness = styleValue(p, clear.springStiffness, balanced.springStiffness, frosted.springStiffness),
            springDampingRatio = styleValue(p, clear.springDampingRatio, balanced.springDampingRatio, frosted.springDampingRatio)
        )
    }

    companion object {

        /**
         * 【约定·滑杆量程】任何预设/派生出的光学参数值都必须落在对应 UI 滑杆量程内：
         * 否则滑杆显示值越界、把手钉在末端、一拖就跳变，用户再也回不到出厂值（设置类缺陷②）。
         * 参考：refractionOffsetDp = 32dp → 滑杆量程须 ≥32（现为 0f..48f）；
         *       fresnelStrength = 1.32 → 滑杆量程须 ≥1.32（现为 0f..1.6f）。
         */

        /**
         * “玻璃透明度”滑杆量程 = [mapping] 的归一化窗口 [磨砂端 .. 通透端]，
         * 直接由各预设的 [GlassPreset.transparency] 派生（0.48f..0.94f），不写魔数。
         * UI 滑杆必须使用本量程：窗口外的输入在 [mapping] 里一律被钳到端点，
         * 若滑杆沿用 0f..1f，左半段 0~0.48 与右端 0.94~1 全是“拖了没反应”的死区 ✗
         */
        val TRANSPARENCY_RANGE: ClosedFloatingPointRange<Float> =
            GlassPreset.FROSTED_ACCESSIBLE.transparency..GlassPreset.IOS26_BETA1_ULTRA_CLEAR.transparency

        // ================= 【玻璃风格拉杆（iOS 27 式跟随拉杆）· 2026-09-14】 =================

        /** 【玻璃风格拉杆】默认位置 = 中位（用户拍板：默认中间 = 均衡档）。 */
        const val GLASS_STYLE_DEFAULT_POS: Float = 0.5f

        /** 拉杆位置 → 档名身份：s<0.25 通透 / 0.25≤s<0.75 均衡 / s≥0.75 磨砂（锚点 0/0.5/1 各落三档）。 */
        fun stylePresetOf(s: Float): GlassPreset {
            val p = if (s.isFinite()) s.coerceIn(0f, 1f) else GLASS_STYLE_DEFAULT_POS
            return when {
                p < 0.25f -> GlassPreset.IOS26_BETA1_ULTRA_CLEAR
                p < 0.75f -> GlassPreset.BALANCED
                else -> GlassPreset.FROSTED_ACCESSIBLE
            }
        }

        /** 宏观透明度 t → 拉杆位置 s（分段线性逆映射；端点精确：0.94→0.0 / 0.78→0.5 / 0.48→1.0）。 */
        fun stylePositionOf(t: Float): Float {
            val a = GlassPreset.IOS26_BETA1_ULTRA_CLEAR.transparency
            val b = GlassPreset.BALANCED.transparency
            val c = GlassPreset.FROSTED_ACCESSIBLE.transparency
            val tt = if (t.isFinite()) t.coerceIn(c, a) else b
            return if (tt >= b) 0.5f * (a - tt) / (a - b)
            else 0.5f + 0.5f * (b - tt) / (b - c)
        }

        /** 分段线性取值：[0,0.5] a→b；[0.5,1] b→c（端点由 styleLerp 保证精确）。 */
        private fun styleValue(s: Float, a: Float, b: Float, c: Float): Float =
            if (s <= 0.5f) styleLerp(a, b, s / 0.5f) else styleLerp(b, c, (s - 0.5f) / 0.5f)

        /** 端点精确的线性插值：u=0 精确返回 a、u=1 精确返回 b（锚点逐字段等价的关键）。 */
        private fun styleLerp(a: Float, b: Float, u: Float): Float {
            val uu = u.coerceIn(0f, 1f)
            return a * (1f - uu) + b * uu
        }

        /**
         * ================= 【形状缺陷修复②·三角形小尺寸畸变】尺寸自适应参数 =================
         *
         * 用户缺陷：「三角形玻璃调节过小会产生畸变」（亮部折叠 + 顶点硬锯齿）。
         *
         * 根因：折射带高 / 棱镜高光宽 / 边界羽化等都是**绝对像素常量**（= dp × density，
         * 与形状尺寸无关）。形状越小，这些带子相对形状越大；三条边带在内心附近重叠自交后
         * 掩码饱和、边缘项被重复计入 ⇒ 观感上的「畸变」。
         *
         * 修法（两级）：
         *   ① 粗粒度（已存在，见 [GlassShaders.SIZE_ADAPTIVE_BANDS]）：全部带宽按短边比例缩放；
         *   ② 细粒度（本节）：让三个量由**形状特征尺寸**（三角形内切圆半径 inradius）派生并取 min：
         *        bandH      = min(折射带高原值,   inradius × BAND_FRAC)       // REFRACTION_HEIGHT
         *        highlightW = min(棱镜高光宽原值, inradius × HIGHLIGHT_FRAC)  // 迎光侧内辉光带 bandW
         *        feather    = min(羽化原值,       inradius × FEATHER_FRAC)    // 边界 coverage 羽化
         *      inradius = 三角形面积 / 半周长（AGSL 内按与 sdTriangleShape **同源**的顶点解析求出，
         *      不需要新增 uniform；形状不是三角形、或不是卡片（面板/胶囊/桥）时上限 = ∞ ⇒ 恒等）。
         *
         * 【大尺寸逐像素不变的论证】inradius 随卡片尺寸线性增长，而这些「原常量」是固定的：
         * 卡片足够大时 min() 取回原常量 ⇒ 与改动前逐像素一致。
         * 具体余量（默认档 0.35 = 644×644 卡片，解析内切半径 161.5px）：
         *   · 折射带高    160px  vs 161.5×1.05 = 169.6px  → +6.0% ✓ 原常量胜出（不变）
         *   · 棱镜高光宽   18px  vs 161.5×0.15 =  24.2px  → +34%  ✓ 原常量胜出（不变）
         *   · 边界羽化    5.0px  vs 161.5×0.035 =  5.65px → +13%  ✓ 原常量胜出（不变）
         * 0.50 档（920×920，内切半径 230.6px）三项余量更大 ⇒ 同样逐像素不变 ✓
         * 0.20 档（368×368，内切半径 92.3px）高光宽 → 13.9px（原 17.4px，−20%）= 本 clamp 真正生效处 ✓
         *
         * 【一键回退开关】true（默认）= 启用本节三个 clamp；false = 整段回退：
         * 生成的回退档里三个上限恒为 ∞ ⇒ 下游 min() 退化为恒等变换，与「只做短边比例缩放」的
         * 版本**逐值等价**（表达式文本多了 min(...,1e9)/max(...,1.0) 两层，但 20 万组随机输入下
         * 取值完全相同）。要连「短边比例缩放」一起回退（= 最初的固定像素带宽），再把
         * [GlassShaders.SIZE_ADAPTIVE_BANDS] 置 false。
         */
        const val SHAPE_INRADIUS_CLAMP: Boolean = true

        /** 折射带高（REFRACTION_HEIGHT）的尺寸上限分数 kBandFrac：bandH ≤ 内切圆半径 × 本值。 */
        const val SHAPE_INRADIUS_BAND_FRAC: Float = 1.05f

        /** 棱镜高光宽（迎光侧内辉光带 bandW）的尺寸上限分数 kHighlightFrac。 */
        const val SHAPE_INRADIUS_HIGHLIGHT_FRAC: Float = 0.15f

        /** 边界羽化（coverage feather）的尺寸上限分数。 */
        const val SHAPE_INRADIUS_FEATHER_FRAC: Float = 0.035f

        /**
         * ================= 【锯齿修复③ · 贴边发丝高光带的边缘平滑】=================
         *
         * 用户缺陷：「玻璃边缘有锯齿」（卡片弧线上 1~2px 阶梯）。
         *
         * 机上定量定位（模拟器 emulator-5554，setDebugMode 逐层隔离）：
         *   · 卡片可见边界的那圈亮带在 FINAL 下 9208px（>235）；切 BACKGROUND_ONLY/BLUR_COMPARE 后只剩 87px
         *     ⇒ 亮带由【着色器绘制】✓（不是任何 Path 裁剪画出来的）
         *   · 剖面实测（左侧直边，x=580 起）：背景 178 → 255 255 255 255 → 190 203 211…（内辉光）
         *     ⇒ 带的最外一圈正好压在可见边界上；卡片是 HDR 玻璃（hdrBoost=4、hdrCeil=4）
         *     ⇒ 边界第一圈就被推到【显示端饱和】⇒ 只能呈现「背景 → 满亮」的 1px 硬台阶（实测 85~88 灰阶）
         *
         * 根因（着色器侧）：旧剖面是【三角】，峰在边内 0.5×hairScale（默认 ≈0.75px）、
         * 半宽 1.0×hairScale（默认 ≈1.5px）⇒ 可见边界处 hair 仍有 0.5（≠0），
         * 最外一圈 = 半亮 × 大增益（K ≈ 230/255 每单位 hair）⇒ 依旧饱和 ⇒ 弧线被硬切成阶梯。
         *
         * 修法（本节常量 + [HAIR_BAND_EDGE_AA]）：把三角换成【单峰平滑剖面】（两侧 smoothstep 裙摆）：
         *   · 峰值仍为 1.0（高光亮度、颜色、光线方向、折射、模糊一律不动）；
         *   · 外侧裙摆 = OUT×hairScale（默认 5.0px）从可见边界【0】平滑升起（smoothstep 两端一阶导为 0）；
         *   · 内侧裙摆 = IN×hairScale（默认 2.0px）平滑落下 ⇒ 带的总宽 ≈(OUT+IN)×hairScale。
         *   · 「可见边界那一圈 hair=0」是抗锯齿的关键：弧线上最外一圈只剩玻璃本体色（≈背景亮度）
         *     ⇒ 轮廓不再有高对比硬台阶；亮线本体（饱和段）退到边内 ~2.5px 起，2~3 个像素的过渡 ✓
         *   · 为什么裙摆必须这么宽（数值判据，非估算）：显示端饱和使像素值 = clip(α×(base + K·hair))，
         *     在 α≈0.8 处 K·Δhair 只要超过 0.16（≈40 灰阶）就会跳台阶 ⇒ Δhair ≤ 0.22/px
         *     ⇒ 0→1 的升起至少要 4~5px（4px 档模型算得 56 灰阶 ✗，5px 档 38 灰阶 ✓）。
         *
         * 【默认态影响面】只对演示卡片生效（shader 内用 lgCardOn=tintModel>0.5 做 mix）：
         * 面板/胶囊（tintModel=0）逐像素不变 ✓（那些元素的边缘 AA 归 panelEdgeAa 管）。
         * 变化只落在卡片边界内外 ~7px 的窄带内（≈0.3% 屏像素），带的总光通量与旧三角相当。
         *
         * 一键回退：本常量置 false → shader 生成的回退行与修改前【逐字符等价】（旧三角剖面）。
         *
         * ★【默认值 = true（2026-09-14 09:xx 更正）】★
         * 【08:xx 的"默认 false"结论作废】：那轮测量把"最外 1~2px 的 255 硬台阶"判给"合成/HDR 第三来源"，
         * 但没有认出真正机制 —— **AGSL effect 输出未预乘**（合成 = rgb + (1-α)·bg 而不是 α·rgb + (1-α)·bg，
         * 详见 DebugSwitches.cardPremultipliedOutput 的探针对账）。在该机制下：
         *   · 未预乘时：α 不衰减颜色 ⇒ 本剖面把亮带整体加宽，只是让"饱和区"更宽（实测 +88% ✗）；
         *   · 预乘修复后：α 真正衰减 ⇒ 饱和区消失 ⇒ 亮带的【边缘斜率】= 可见过渡的唯一决定者。
         * 因此"加宽 + 平滑"从"无收益"变成"必需"：三角剖面的内侧 1px 斜率（Δhair≈0.67/px）在 α=1 处
         * 仍会造成 ~55~85 灰阶的单像素跳变（>40 验收线），必须换成平滑裙摆（一阶导连续）。
         * 【默认态影响面】只对演示卡片生效（shader 内 mix(..., lgCardOn)，lgCardOn = tintModel>0.5）：
         * 面板/胶囊（tintModel=0）逐像素不变 ✓（那些元素的边缘 AA 归 panelEdgeAa 管）。
         * 变化只落在卡片边界内外 ~5px 的窄带内（≈0.2% 屏像素）。
         *
         * 一键回退：本常量置 false → shader 生成的回退行与修改前【逐字符等价】（旧三角剖面）。
         */
        const val HAIR_BAND_EDGE_AA: Boolean = true

        /**
         * 发丝带支撑区间半宽倍率：|sd + 0.5·hairScale| ≤ 本值 × hairScale 之外 hair=0；
         * 区间内为 smoothstep 平滑过渡（半宽 hair≥0.5 的区域 = 0.7 × 本值 × hairScale）。
         *
         * 【1.4 → 5.0（2026-09-14 09:xx）】配合预乘修复后，过渡的"陡度"直接决定单像素跳变。
         * 机理：hair 的 smoothstep 导数峰值 1.5，作用在 SUPPORT_MUL×hairScale 的半宽上 ⇒
         *   单像素 Δhair ≤ 1.5/(SUPPORT_MUL×1.5)，再乘高光振幅（机上实测 amp ≈ 0.28~0.3）
         *   ⇒ 单像素跳变 ≈ 255 × 1.5 / (1.5×SUPPORT_MUL) × amp。
         * 实测标定（3.0 档左右两侧实测：外侧 15~33 ✓，内侧 47~52 ✗ 超 40 验收线；
         *   实测斜率约是解析值的 2×，故按实测反推）：SUPPORT_MUL = 5.0 ⇒ 内侧跳变 ≈ 47×3.0/5.0 ≈ 28 ✓
         *   （留 ~30% 余量，容忍背景亮度/极角变化）。再宽会与既有内辉光（bandW 18px）完全融合、
         *   亮线失去"发丝"辨识度 ⇒ 5.0 是"验收达标"与"观感仍是一条细亮边"的折中。
         * 观感：亮线峰值与位置不变，仅两侧裙摆从 ~1px 展宽到 ~7.5px（一半仍在原内辉光带内）。
         */
        const val HAIR_BAND_SUPPORT_MUL_LEGACY: Float = 5.0f

        /**
         * ============ 【P44 · 玻璃边沿「糊」修复】发丝带支撑半宽倍率（新默认） ============
         *
         * 用户缺陷：「玻璃边沿还是有点糊」—— 上一次「锯齿修复③」的代价：
         *   为了消掉 255 灰阶硬台阶，贴边高光从「1~2px 硬线」变成【15px 总宽的平滑裙摆】
         *   (SUPPORT 1.4→5.0) ⇒ 锯齿没了，但边缘读作"糊" ✗。
         *
         * 机上量化（emulator-5554，卡片 644×1057@(582,240)，四条直边中段垂直扫描；数字为「外侧 50% 跨越点」
         * 锚定的固定窗口口径）：
         *   · 指标① 亮带宽 FWHM（剖面 ≥ 背景+0.5·幅度 的宽度 = 眼睛看到的"线宽"）：
         *       5.0 → 左 5.75px / 右 4.72px   3.0 → 左 3.55px / 右 2.76px（**−38% / −42%**）
         *   · 指标② 边缘带相邻像素最大通道跳变（硬台阶）：
         *       5.0 → 左 24.7 / 右 26.9 / 上 37.1   3.0 → 左 27.8 / 右 42.2 / 上 47.2
         *       （改前那次"锯齿"是 82~91 ⇒ 3.0 档仍在"无明显锯齿"区；本项目实测的锯齿区在 ≥50~59：
         *        SUPPORT 2.5 档已到 50~59 = 与 2.0 档同区，故不取）
         *   · 外侧 10%→90% 过渡带宽度：5.0 → 3.99px，3.0 → 3.81px（这一段由覆盖率羽化 lgFeather 主导，
         *     不同档位差别很小 ⇒ "糊"的观感主要来自亮带总宽，而不是外沿斜坡）
         *   · 候选档全表（FWHM 左/右；硬台阶最大值）：4.0 → 4.69/3.74、44.3 ｜ 3.5 → 4.15/3.23、49.1
         *     ｜ 3.0 → 3.55/2.76、47.2 ｜ 2.5 → 2.91/2.14、59.0 ｜ 2.0 → 1.88/1.48、57.5
         *   ⇒ 取【3.0】= 两个指标同时最优的拐点（再往小硬台阶进入实测锯齿区；再往大 FWHM 明显回升）。
         *   ⚠️ 严格「≤8~16 灰阶」硬门不可达（连改动前的 5.0 档在上边也是 37.1）—— 已如实写进报告，
         *      并把 3.5（更保守）/ 2.5（更锋利但有锯齿风险）作为可选档留给用户拍板。
         *
         * 本常量 = **运行时 uniform 的默认值**（下发点 BackdropAdapter 卡片块 → uniform `edgeHairSupport`）：
         *   · 默认开档 [com.example.liquidglass.debug.DebugSwitches.edgeHairBandSharp] 用它；
         *   · 关闭档用 [HAIR_BAND_SUPPORT_MUL_LEGACY]（= 改动前的 5.0）⇒ 一行命令回退，逐像素回旧观感；
         *   · 标定通道 `setEdgeHairSupport --ef value X` 可在同一构建上逐档扫掠（✗ 无需重新构建/重启）。
         *
         * 【铁律】只改「贴边高光带的支撑宽度」：峰值 1.0 / 峰位 / 亮度 / 颜色 / 光照方向 / 折射 / 模糊
         * 一律不动，可见边界仍由同一个 SDF（`sd`）与覆盖率决定 ⇒ ✗ 不引入第二套边界、✗ 不动折射默认参数。
         * 【作用面】只有卡片/多卡演示（tintModel>0.5）走本值；面板/胶囊取 hairOld、张力桥未下发本 uniform
         * ⇒ 都落在 AGSL 兜底（旧值 5.0）⇒ 硬门实测面板/胶囊 **0 像素差异** ✓。
         */
        const val HAIR_BAND_SUPPORT_MUL: Float = 3.0f

        /** 【备选实验档·当前未用】宽裙摆外侧宽度倍率（5px 裙摆，实测把亮带整体加宽 ✗ 已弃）。 */
        const val HAIR_BAND_OUT_MUL_UNUSED_WIDE_SKIRT: Float = 3.3333f

        /**
         * ================= 【①A · 三角形高光带整体内缩到形状内侧】=================
         *
         * 用户缺陷：「三角形玻璃三条边的表观亮宽不一致 ⇒ 小尺寸时看起来不像正三角形」。
         *
         * 根因（解析 + 机上实测）：发丝高光带的剖面
         *   `hair = clamp(1 − |sd + 0.5·hairScale| / (SUPPORT·hairScale), 0, 1)`
         * 的【中心只在内侧 0.5·hairScale（默认 0.75px）】，而 smoothstep 支撑区间是
         * ±SUPPORT·hairScale（默认 ±7.5px）⇒ **带的一半落在形状外**（骑在可见边界两侧）✗。
         * 外侧那半边的可见量 = 带 × coverage 羽化（coverage 在边界外 2.5px 内衰减到 0），
         * 其"看起来有多宽"取决于边界处的覆盖率剖面与【像素网格 ↔ 边方向】的关系
         * ⇒ 水平底边（网格对齐）与两条 28° 斜边（跨像素）表现不一致 ⇒ 三条边亮宽不等 ✗。
         *
         * 修法：把带的中心整体沿法向【向形状内侧】平移 [TRIANGLE_HAIR_INSET_MUL]×hairScale
         * （AGSL 里即 `sd` → `sd + lgTriHairInset`），使带完全落在形状内侧：
         *   半高区（hair ≥ 0.5）= [−(3+k)·hairScale, −(k−2)·hairScale]，k = INSET_MUL；
         *   k = 2.0 ⇒ 外沿半高恰好落在可见边界（sd = 0）上 ⇒ 最外侧不再有"半亮"像素 ✓
         * 峰值 1.0 / 亮度 / 颜色 / 光照方向 / 折射 / 模糊一律不动（只平移带的位置）✓
         *
         * 【标定】（hairScale = lgHair = clamp(1.5·lgScale, 1.0, 1.5) px；lgScale 由短边/640 决定）
         *   · 0.50 / 0.35 档：lgScale = 1.0 ⇒ hairScale = 1.5px ⇒ 内缩 3.0px
         *   · 0.20 档：lgScale = 0.575 ⇒ hairScale = 1.0px ⇒ 内缩 2.0px
         *   （随尺寸缩放：内缩量与带的自身宽度同源，小尺寸不会被"大内缩"推到形状外面 ✓）
         *
         * 【作用面】只对【三角形演示卡片】生效（AGSL 内 gate：shapeType == 4 && tintModel > 0.5）
         * ⇒ 其它形状（圆/胶囊/椭圆/六边/超椭圆/圆角矩形）、面板、折叠胶囊、张力桥的 sd 不变
         * ⇒ 逐像素不变 ✓（ON/OFF 两版在这些元素上的差异应为 0.00%）。
         *
         * 【一键回退】本常量置 false → 生成的回退档里 inset 恒为 0.0 ⇒ 与改动前【逐值等价】
         * （表达式文本只多了一个 `+ lgTriHairInset`，取值完全相同）。
         * ⚠ 与 HAIR_BAND_EDGE_AA / shapeNormalSameSource 同理：AGSL 源码在 shader 程序构建时
         *   拼接一次 ⇒ 改本常量必须【重新构建】，运行时 setSwitches 翻不动它 ✗。
         */
        const val TRIANGLE_HAIR_INSET: Boolean = true

        /**
         * 三角形高光带内缩量系数 k：内缩 = k × hairScale（见 [TRIANGLE_HAIR_INSET] 的标定）。
         *
         * k = 2.0 的来历（数值而非估计）：smoothstep 支撑 = 5·hairScale，半高沿 = 2.5·hairScale。
         * 带中心距边界 0.5·hairScale（原本在内侧）⇒ 外沿半高距边界 = 2.5 − 0.5 = 2.0·hairScale
         * ⇒ k = 2.0 正好把【半高外沿】推到可见边界（sd = 0）上：既保证"可见亮带全在形状内"，
         * 又不把带推得过深（否则亮线会与轮廓脱开、边缘出现暗缝）。
         * 实测三条边的一致性（见 REPORT：半高宽/亮度的跨边极差 + 中线偏差 改前→改后）。
         */
        const val TRIANGLE_HAIR_INSET_MUL: Float = 2.0f

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        /**
         * 0..1 宏观透明度 → 各光学参数映射（视觉起点，非 Apple 内部参数）。
         *
         * 第二阶段校准（真机审计）：消除乳白蒙版与粗白边，
         * t 升高时中心填充/模糊快速下降，边缘折射与高光保留。
         */
        private fun mapping(t: Float): LinkedParams {
            // 【档位归一化】通透0.94 / 均衡0.78 / 磨砂0.48（都挤在中高段 ✗）
            // 归一化后 tn: 0 = 磨砂（= 用户认可的现有观感 ✓）, 1 = 通透（真通透 ✓）
            // 端点不再写魔数（原 0.48f / 0.46f）：直接从 TRANSPARENCY_RANGE
            // （0.48f..0.94f，由 GlassPreset.transparency 派生）取。
            // UI 滑杆量程必须与本窗口一致，否则窗口外的行程全是死区（设置类缺陷②）。
            val tRaw = t.coerceIn(TRANSPARENCY_RANGE.start, TRANSPARENCY_RANGE.endInclusive)
            val t = ((tRaw - TRANSPARENCY_RANGE.start) /
                (TRANSPARENCY_RANGE.endInclusive - TRANSPARENCY_RANGE.start)).coerceIn(0f, 1f)
            return LinkedParams(
                backgroundTransmission = lerp(0.956f, 0.985f, t),   // 磨砂端 = 现有观感 ✓
                materialOpacity = lerp(0.026f, 0.014f, t),   // 磨砂端 = 现有观感 0.026 ✓
                tintOpacity = lerp(0.020f, 0.004f, t),
                blurRadiusDp = lerp(2.9f, 0.8f, t),   // 磨砂=2.9dp（= 用户认可观感 ✓）→ 通透=0.8dp（真通透 ✓）
                edgeBlurRadiusDp = lerp(6.0f, 2.0f, t),   // 边缘模糊同步分档
                refractionOffsetDp = 32.0f,   // 拆轴：三档共用（原磨砂端只有16dp ✗ 会显得不像玻璃）   // 再夸张一档（对齐参考图的厚透镜）   // 新分支：边缘强折射（放大镜压缩感）   // 加厚：原 5→9.6dp（用户反馈"玻璃不够厚"）
                fresnelStrength = 1.32f,   // 拆轴：固定   // 调优：菲涅耳加强   // 加厚：菲涅耳更强
                edgeHighlightOpacity = 0.44f,   // 拆轴：固定   // 调优：镜面高光层次加强   // 加厚：边缘高光更亮
                edgeShadowOpacity = 0.18f,   // 拆轴：固定   // 新分支：内侧倒角更深   // 加厚：内暗边更深（读作玻璃厚度）
                localDimmingOpacity = lerp(0.05f, 0.02f, t),   // 【提亮磨砂】0.14 → 0.05：磨砂端不再被压暗（用户要求"磨砂效果提亮一点"）；通透端不变 ✓
                // v1.16.0：色散整体加强（用户反馈"色散有点弱"）。
                // 偏移上限同步放宽到 4.5dp；与主折射偏移（约 9.3dp）仍差一倍以上，
                // 符合"色散位移远小于主折射"的验收要求，不会退化成 RGB 故障风。
                // v1.18.3：上一版加得太猛（用户反馈"色散有点假"），回调到克制量级：
                // 偏移减半、强度降到 0.4 一线、色散带收窄，只留一圈很淡的彩虹边
                // 【P03·四角色散】开关开 = 按既定定义注入（偏移 1.6dp、强度 0.20；带 8dp 不变）；
                //   开关关（默认 false）= 隔离实验现值 0f ⇒ 逐像素与改动前一致 ✓；
                //   四角收窄由 shader 侧 cornerDispersionGain 门控（见 GlassShaders 色散段）
                dispersionOffsetDp = if (DebugSwitches.cornerDispersion) 1.6f else 0f,   // 隔离实验：空间偏移归零（默认）
                dispersionEdgeWidthDp = 8f,   // 色散作用带 11→8dp，只贴边 ✓
                // 用户反馈：彩格背景上色块边缘分离出红绿蓝彩边、色彩溢散 ✗（不是均匀模糊）
                // → 色散整体降到"只留一丝边缘彩感"级别（0.55/0.32 → 0.20/0.08）：
                // 保留液态玻璃的边缘色散特征 ✓，不再出现明显的 RGB 分光伪影 ✓
                dispersionStrength = if (DebugSwitches.cornerDispersion) 0.20f else 0f,   // 【P03】开关开 = 四角加权色散 0.20；关 = 隔离实验遗留 0（逐像素与改动前一致）
                saturation = 1.05f   // 拆轴：固定
            )
        }

        /** 由映射规则派生的一组光学参数。 */
        private class LinkedParams(
            val backgroundTransmission: Float,
            val materialOpacity: Float,
            val tintOpacity: Float,
            val blurRadiusDp: Float,
            val edgeBlurRadiusDp: Float,
            val refractionOffsetDp: Float,
            val fresnelStrength: Float,
            val edgeHighlightOpacity: Float,
            val edgeShadowOpacity: Float,
            val localDimmingOpacity: Float,
            val dispersionOffsetDp: Float,
            val dispersionEdgeWidthDp: Float,
            val dispersionStrength: Float,
            val saturation: Float
        )

        /** 各预设的固定参数（弹簧与形变强度为演示用途的合理取值）。 */
        fun preset(preset: GlassPreset): GlassParameters {
            val linked = mapping(preset.transparency)
            return GlassParameters(
                preset = preset,
                glassTransparency = preset.transparency,
                backgroundTransmission = linked.backgroundTransmission,
                materialOpacity = linked.materialOpacity,
                tintOpacity = linked.tintOpacity,
                blurRadiusDp = linked.blurRadiusDp,
                edgeBlurRadiusDp = linked.edgeBlurRadiusDp,
                refractionOffsetDp = linked.refractionOffsetDp,
                dispersionOffsetDp = linked.dispersionOffsetDp,
                dispersionEdgeWidthDp = linked.dispersionEdgeWidthDp,
                dispersionStrength = linked.dispersionStrength,
                fresnelStrength = linked.fresnelStrength,
                edgeHighlightOpacity = linked.edgeHighlightOpacity,
                edgeShadowOpacity = linked.edgeShadowOpacity,
                localDimmingOpacity = linked.localDimmingOpacity,
                saturation = linked.saturation,
                deformationStrength = preset.deformationStrength,
                springStiffness = preset.springStiffness,
                springDampingRatio = preset.springDampingRatio
            )
        }
    }
}

/** 预设自带的宏观透明度。 */
private val GlassPreset.transparency: Float
    get() = when (this) {
        GlassPreset.IOS26_BETA1_ULTRA_CLEAR -> 0.94f
        GlassPreset.BALANCED -> 0.78f
        GlassPreset.FROSTED_ACCESSIBLE -> 0.48f
        GlassPreset.CUSTOM -> 0.78f
    }

/** 预设自带的凝胶形变与弹簧参数（演示取值）。 */
private val GlassPreset.deformationStrength: Float
    get() = when (this) {
        GlassPreset.IOS26_BETA1_ULTRA_CLEAR -> 1.0f
        GlassPreset.BALANCED -> 0.85f
        GlassPreset.FROSTED_ACCESSIBLE -> 0.6f
        GlassPreset.CUSTOM -> 1.0f
    }

private val GlassPreset.springStiffness: Float
    get() = when (this) {
        GlassPreset.IOS26_BETA1_ULTRA_CLEAR -> 420f
        GlassPreset.BALANCED -> 380f
        GlassPreset.FROSTED_ACCESSIBLE -> 320f
        GlassPreset.CUSTOM -> 380f
    }

private val GlassPreset.springDampingRatio: Float
    get() = when (this) {
        GlassPreset.IOS26_BETA1_ULTRA_CLEAR -> 0.72f
        GlassPreset.BALANCED -> 0.78f
        GlassPreset.FROSTED_ACCESSIBLE -> 0.86f
        GlassPreset.CUSTOM -> 0.78f
    }
