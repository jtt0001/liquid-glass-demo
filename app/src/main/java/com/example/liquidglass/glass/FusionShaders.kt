// ---------------------------------------------------------------------------
// 移植声明 / Porting notice
// 本文件部分代码移植/改编自 QWEA0/Liquid-Glass-Android（https://github.com/QWEA0/Liquid-Glass-Android），
// 以 MIT License 授权，版权行逐字如下：
//   Copyright (c) 2025-2026 pandadog
// 许可全文见本仓库 LICENSES/ 与 THIRD_PARTY_NOTICES.md；MIT 要求版权声明与许可声明随副本一并提供，已满足。
// ---------------------------------------------------------------------------
package com.example.liquidglass.glass

import android.util.Log

/**
 * 【P12 · 邻近流体融合（备选方案 B）】AGSL 片段（新增文件；本文件不改任何现有渲染机制，
 * 只往现有 AGSL 源码里【注入】一段可开关的联合场代码）。
 *
 * 路径（README「待实现：液滴张力融合」首选路径，另加「颈部」项解决直边 smooth-min 的突跳）：
 *   1) 卡片 Shader 新增 shape2（对方卡 rect + 圆角）与 fuseK（smooth-min 融合半径）uniform，
 *      在 sdShape/gradShape 的【同一套场】上做多项式 smooth-min：
 *        h = clamp(0.5 + 0.5*(b-a)/k)；smin = mix(b,a,h) - k*h*(1-h)；法线 = h*∇a + (1-h)*∇b
 *      k→0 时 h 严格取 0/1、结果【逐像素等于 min(a,b)】⇒ 未触发时零回归（硬要求）。
 *   2) 两卡最近点之间并入一段【圆角矩形条】SDF（颈部/细丝同源，复用 sdRoundedRect）：
 *      靠近阶段 = 浸润融合区（半径随融合进度增长；完全贴合时 = 较短卡半高 + 余量 ⇒ 轮廓连成一体，
 *      且【不会】像纯 smooth-min 那样在直边上从 0 突跳到整条边 —— 融合宽度随帧连续爬升）；
 *      拉开阶段 = 细水丝（半径随 filamentLifetime 收缩到 0 ⇒ 断裂分离）。
 *   3) 颈部/细丝是【玻璃本体的一段】：sd 与法线都并入同一个场 ⇒ 下游的折射 / 菲涅尔 /
 *      背景采样 / 边缘高光 / 色散全部【原样复用】，绝无纯色填充（本文件只改场，不改光学）。
 *
 * 注入点（三处，全部【整行文本锚点】；由 GlassShaders.source() 调用 [injectInto] 一次性拼接）：
 *   A) HEADER 的 `layout(color) uniform half4 materialColor;` 行后 → uniform 声明；
 *   B) MAIN 的 `float sd = sdShape(...)` 行后 → 联合场片段（改 sd、产出融合法线 lgFuseNormal）；
 *   C) MAIN 的 `float2 normal = gradShape(...)` 行后 → 融合活跃时用法线覆盖一行。
 * 任一锚点未命中 ⇒ 打 Log.e（tag LGFusion）且该处不注入（绝不静默产出半截 shader；
 * 缺 A 会导致 setFloatUniform 静默无效，缺 B/C 等于融合不可用 —— 日志里一眼可判）。
 *
 * 纪律：本片段不使用 '%' 运算符、不使用 gl_FragCoord / resolution / time 等内置名，
 * 只调用本工程既有函数（sdShape / gradShape / sdRoundedRect / safeNormalize）。
 */
object FusionShaders {

    private const val TAG = "LGFusion"

    /** 锚点 A：HEADER 里最后一条 uniform 声明（插入点紧随其后）。 */
    private const val UNIFORM_ANCHOR = "layout(color) uniform half4 materialColor;\n"

    /** 锚点 B：MAIN 里唯一一次 sd 计算（插入联合场片段）。 */
    private const val SD_ANCHOR =
        "    float sd = sdShape(centered, shapeHalf, radius, shapeType);\n"

    /** 锚点 C：MAIN 里唯一一次法线计算（插入融合法线覆盖行）。 */
    private const val NORMAL_ANCHOR =
        "    float2 normal = gradShape(centered, shapeHalf, gradRadius, shapeType);\n"

    /** 新增 uniform 声明（未 set 时全部为 0 ⇒ 不融合、逐像素等于改动前）。 */
    val uniforms: String = "" +
        "// ---- 【P12·邻近流体融合】新增 uniform（默认全 0 = 不融合 ⇒ 与改动前逐像素一致）----\n" +
        "uniform float2 fuseOffset2;    // 对方卡中心（本卡节点局部坐标 px；局部原点 = 卡片中心）\n" +
        "uniform float2 fuseHalf2;      // 对方卡半尺寸（px）\n" +
        "uniform float fuseRadius2;     // 对方卡圆角半径（px）\n" +
        "uniform int fuseShape2;        // 对方卡形状类型（与 shapeType 同一套序数）\n" +
        "uniform float fuseK;           // smooth-min 融合半径（px；0 = 不融合）\n" +
        "uniform float2 fuseNeckCenter; // 颈部/细丝中心（本卡节点局部坐标 px）\n" +
        "uniform float2 fuseNeckAxis;   // 颈部/细丝轴向（单位向量）\n" +
        "uniform float fuseNeckHalf;    // 颈部/细丝半长（px）\n" +
        "uniform float fuseNeckR;       // 颈部/细丝半径（px；0 = 无颈部/无丝）\n" +
        "uniform float fuseNeckRc;      // 颈部/细丝端部圆角（px）\n"

    /** 联合场片段（插在 MAIN 的 sd 行之后；对 sd / 法线的修改与现有下游完全兼容）。 */
    val fieldCode: String = "" +
        "    // ============ 【P12·邻近流体融合】联合场（fuseK 与 fuseNeckR 同为 0 时本段零作用 ⇒ 逐像素等于改动前）============\n" +
        "    // 参考上游 QWEA0/Liquid-Glass-Android（MIT）的 shape2 + blendK + sminPoly 段：\n" +
        "    // 多项式 smooth-min 与法线的解析权重（∂/∂a = h、∂/∂b = 1-h）保证【SDF 与法线同源】；\n" +
        "    // k→0 时 h 取 0/1 ⇒ 结果严格退化为 min(a,b)（未触发时不可能产生任何像素差异）。\n" +
        "    float2 lgFuseNormal = float2(0.0);\n" +
        "    if (max(fuseK, fuseNeckR) > 0.0001) {\n" +
        "        float2 lgFieldN = gradShape(centered, shapeHalf, gradRadius, shapeType);\n" +
        "        float lgFsd = sd;\n" +
        "        float lgFK = max(fuseK, 0.0);\n" +
        "        if (lgFK > 0.0001) {\n" +
        "            // ① 对方卡并入\n" +
        "            float2 lgC2 = centered - fuseOffset2;\n" +
        "            float lgSd2 = sdShape(lgC2, fuseHalf2, fuseRadius2, fuseShape2);\n" +
        "            float2 lgG2 = gradShape(lgC2, fuseHalf2, fuseRadius2, fuseShape2);\n" +
        "            float lgH1 = clamp(0.5 + 0.5 * (lgSd2 - lgFsd) / lgFK, 0.0, 1.0);\n" +
        "            lgFsd = mix(lgSd2, lgFsd, lgH1) - lgFK * lgH1 * (1.0 - lgH1);\n" +
        "            lgFieldN = safeNormalize(lgH1 * lgFieldN + (1.0 - lgH1) * lgG2);\n" +
        "        }\n" +
        "        if (fuseNeckR > 0.0001) {\n" +
        "            // ② 颈部/细丝（两卡最近点之间的圆角矩形条）并入：旋转到条的轴向坐标系后复用 sdRoundedRect\n" +
        "            float2 lgAx = safeNormalize(fuseNeckAxis + float2(0.0001, 0.0));\n" +
        "            float2 lgPe = float2(-lgAx.y, lgAx.x);\n" +
        "            float2 lgRel = centered - fuseNeckCenter;\n" +
        "            float2 lgRot = float2(dot(lgRel, lgAx), dot(lgRel, lgPe));\n" +
        "            float2 lgNH = float2(max(fuseNeckHalf, 0.5), max(fuseNeckR, 0.5));\n" +
        "            float lgNRc = clamp(fuseNeckRc, 0.0, min(lgNH.x, lgNH.y));\n" +
        "            float lgSdN = sdRoundedRect(lgRot, lgNH, lgNRc);\n" +
        "            // 颈部法线：数值梯度（与项目其它形状同一做法）；旋转是正交变换 ⇒ 梯度直接映射回本卡坐标\n" +
        "            float lgEx = lgSdN - sdRoundedRect(lgRot - float2(1.0, 0.0), lgNH, lgNRc);\n" +
        "            float lgEy = lgSdN - sdRoundedRect(lgRot - float2(0.0, 1.0), lgNH, lgNRc);\n" +
        "            float2 lgGn = safeNormalize(float2(lgEx * lgAx.x + lgEy * lgPe.x, lgEx * lgAx.y + lgEy * lgPe.y));\n" +
        "            float lgKn = max(lgFK, clamp(fuseNeckR * 2.0, 6.0, 90.0));\n" +
        "            float lgH2 = clamp(0.5 + 0.5 * (lgSdN - lgFsd) / lgKn, 0.0, 1.0);\n" +
        "            lgFsd = mix(lgSdN, lgFsd, lgH2) - lgKn * lgH2 * (1.0 - lgH2);\n" +
        "            lgFieldN = safeNormalize(lgH2 * lgFieldN + (1.0 - lgH2) * lgGn);\n" +
        "        }\n" +
        "        sd = lgFsd;\n" +
        "        lgFuseNormal = lgFieldN;\n" +
        "    }\n"

    /** 法线覆盖片段（插在 MAIN 的 normal 行之后；未融合时保持原值）。 */
    val normalCode: String = "" +
        "    // 【P12】融合活跃时改用联合场法线（未活跃 ⇒ 保持上面的原值，逐像素不变）\n" +
        "    if (max(fuseK, fuseNeckR) > 0.0001) { normal = lgFuseNormal; }\n"

    /**
     * 把融合片段注入到已有 AGSL 源码（三处整行锚点各命中一次）。
     *
     * 幂等性说明：本函数只在 GlassShaders.source() 里对【刚拼好的】源码调用一次/档位，
     * 重复调用会因为锚点已被替换而命中失败（打 Log.e），因此绝不重复注入。
     */
    fun injectInto(src: String): String {
        val missing = ArrayList<String>(3)
        var out = src
        if (!out.contains(UNIFORM_ANCHOR)) {
            missing.add("uniforms")
        } else {
            out = out.replace(UNIFORM_ANCHOR, UNIFORM_ANCHOR + uniforms)
        }
        if (!out.contains(SD_ANCHOR)) {
            missing.add("field")
        } else {
            out = out.replace(SD_ANCHOR, SD_ANCHOR + fieldCode)
        }
        if (!out.contains(NORMAL_ANCHOR)) {
            missing.add("normal")
        } else {
            out = out.replace(NORMAL_ANCHOR, NORMAL_ANCHOR + normalCode)
        }
        if (missing.isEmpty()) {
            Log.i(
                TAG,
                "融合 AGSL 注入 OK（uniforms/field/normal 三锚点全命中；len=" + out.length +
                    "，fuseK 默认 0 ⇒ 未设置时行为与改动前逐像素一致）"
            )
        } else {
            Log.e(
                TAG,
                "融合 AGSL 注入失败：未命中锚点 " + missing +
                    "（融合不可用；已命中的部分保持注入，未命中的保持原样）"
            )
        }
        return out
    }
}
