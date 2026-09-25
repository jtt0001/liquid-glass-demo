package com.example.liquidglass.diamond

/**
 * 【3D 钻石演示】AGSL uniform 名称统一常量管理。
 *
 * 与 [com.example.liquidglass.glass.GlassUniforms] 同一套纪律：所有 Kotlin 侧
 * `setFloatUniform / setFloatUniform(name, x, y, z)` 用的名称都必须来自这里，
 * 避免手写字符串拼错导致 uniform 静默失效（AGSL 对未设置的 uniform 不报错，只用默认值 = 0）。
 *
 * 铁律：不得使用 AGSL 内置 uniform 名 `resolution` / `time` / `frame` / `date`
 *（重复声明会让 RuntimeShader 编译失败 = 打开钻石页立刻闪退）。下面这些名字都避开了它们。
 *
 * 命名与 `tools/verify_diamond_geometry.py` 的静态检查对应：该脚本会解析本文件的常量，
 * 与 [DiamondAgsl] 源码里的 `uniform` 声明做**双向**比对（少一个 / 多一个都判 FAIL）。
 */
object DiamondUniforms {

    /** uniform shader：背景采样源（父会话用 `uniformShaderName = DiamondUniforms.INPUT` 绑定）。 */
    const val INPUT = "content"

    /** float2 图层尺寸 px（注意：不能用 AGSL 内置名 resolution）。 */
    const val RES = "uRes"

    /** float2 钻石中心（图层坐标 px）。 */
    const val CENTER = "uCenter"

    /** float 腰棱半径 px（模型半径 1.0 对应的屏幕像素半径）。 */
    const val RADIUS = "uRadius"

    /** float 相机距离（半径倍数；默认 3.6）。 */
    const val CAM_Z = "uCamZ"

    /** float 背景平面深度（半径倍数；默认 6.0）。 */
    const val DEPTH = "uDepth"

    /** float3 旋转矩阵第 0 行（world = (dot(row0,v), dot(row1,v), dot(row2,v))）。 */
    const val ROW0 = "uRow0"

    /** float3 旋转矩阵第 1 行。 */
    const val ROW1 = "uRow1"

    /** float3 旋转矩阵第 2 行。 */
    const val ROW2 = "uRow2"

    /** float 折射率（默认 2.417 = 金刚石）。 */
    const val IOR = "uIor"

    /** float 色散强度（0 = 关；>0 时 RGB 三条射线：ior*(1+d) / ior / ior*(1-d)）。 */
    const val DISPERSION = "uDispersion"

    /** float 内部弹射上限 0..4（0 = 单次折射、内部不弹射）。 */
    const val BOUNCES = "uBounces"

    /** float 调试模式：0=FINAL 1=面法线着色 2=面 ID 着色 3=仅折射(无色散/Fresnel) 4=背景直通。 */
    const val DEBUG = "uDebug"

    /** float 边缘 AA 宽度 px。 */
    const val AA = "uAA"

    /** float Fresnel 反射项强度 0..1。 */
    const val FRESNEL = "uFresnel"

    /**
     * float 环境采样模型（【本轮的修复开关】）：
     *  · 1（默认）= 几何一致环境：出射/反射射线与「背景平面 z=−uDepth」或它的镜像平面（朝上时）求交，
     *    再用同一个针孔逆投影 P.xy·uCamZ/(uCamZ+uDepth) 得到采样像素 —— 表面反射 = 背景平面的真实镜像，
     *    内部回光 = 从场景另一侧看到同一张背景；透射路径同时按 (1−R_入)·(1−R_内) 做能量加权。
     *  · 0 = 旧版回退：朝上/退化射线退回「方向×0.5」软投影、透射路径不打折（与 744aef1 行为一致）。
     * 一行可回退 ⇒ 任何光学改动都必须保留这条退路（本项目铁律）。
     */
    const val ENV_MODEL = "uEnvModel"

    /**
     * float 【H3 弹射用尽·物理收尾】开关（【本轮修复开关】）：
     *  · 1（默认）= 弹射用尽后【补链】：内部预算放宽到 `uBounces + min(uBounces, 6)`，让光路继续走到
     *    真正的出射面（弹射上限是质量/性能档位，不是物理上限）；仍走不出去时，收尾能量按最后界面的
     *    透射率 (1−R) 计（TIR 无损 ⇒ 唯一损耗在最终出射界面），不再用拍脑袋的 0.5。
     *  · 0 = 旧口径：预算 = uBounces、用尽 ⇒ 固定 0.5 经验衰减（744aef1/8100724 的逐字行为）。
     *  为什么必须补链：预算用尽会把相邻像素切成"一个出射 / 一个用尽"，亮度台阶 = (1−R)−0.5 ≈ 0.33
     *  ⇒ 正面看就是一条横贯宝石的硬边带（用户报的"内部三层横向折射"）。
     *  一行可回退 ⇒ 任何光学改动都必须保留这条退路（本项目铁律）。
     */
    const val TRAPPED_FIX = "uTrappedFix"

    /**
     * float 【H4 逆投影 y 符号】开关（【本轮修复开关】）：
     *  · 1（默认）= 世界点 P → 屏幕像素的正确投影 py = CY − P.y·k·R（k = uCamZ/(uCamZ+uDepth)），
     *    即 primaryDir 的逆映射、也是 744aef1 平面支路/bgCoordSoft 的同号口径 ⇒ 内部回光与表面镜面
     *    的采样内容与场景上下方向一致。
     *  · 0 = 逐字回到 H1 原样（y 双重取反：采样点关于屏幕水平中线镜像 ⇒ 内部内容上下颠倒），
     *    仅用于同一构建内的 A/B 对照；✗ 不是被否定的"方向×0.5 软投影"分支。
     *  一行可回退 ⇒ 任何光学改动都必须保留这条退路（本项目铁律）。
     */
    const val ENV_Y_FIX = "uEnvYFix"

    /** 全部 uniform 名（按声明顺序；供调试 dump / 批量设置时遍历，避免漏设）。 */
    val allNames: List<String> = listOf(
        INPUT, RES, CENTER, RADIUS, CAM_Z, DEPTH,
        ROW0, ROW1, ROW2, IOR, DISPERSION, BOUNCES, DEBUG, AA, FRESNEL, ENV_MODEL, TRAPPED_FIX, ENV_Y_FIX
    )
}
