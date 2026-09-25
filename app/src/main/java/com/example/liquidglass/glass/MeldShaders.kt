package com.example.liquidglass.glass

import android.util.Log

/**
 * 【P07 · 双卡融合 meld】AGSL 片段（新增文件；只往现有 AGSL 源码里【注入】两小段可开关的代码，
 * 不改任何现有渲染机制、不新增第二套并集实现）。
 *
 * 为什么需要这一小段（官方口径的硬要求）：
 *   · 官方 iOS26 的 meld = 两块玻璃【并成一块连续玻璃】+ **共享采样区** ⇒ 并集区必须【只被绘制一次】
 *     （一次折射、一条轮廓、一条高光）。P12 的联合场（见 FusionShaders）是【两卡各画一次】
 *     （每张卡都往自己的场里并入颈部）⇒ 重叠区会叠两次 ⇒ 用户能看到的「双边框 / 双亮带」✗。
 *   · 本文件补的正是这一件事：【归属划分】—— 并集区每个像素只由【更近的那块卡】绘制
 *     （判据 sd_self ≤ sd_other，与平滑并集的 Voronoi 划分同源），非归属方把 sd 顶到正无穷 ⇒
 *     coverage=0 ⇒ 走 shader 既有的「覆盖率早退」直接透明返回（零成本，连折射都不算 ✓）。
 *
 * 注入点（两处，全部【整行/整段文本锚点】；由 GlassShaders.source() 调用 [injectInto]，排在
 * FusionShaders.injectInto 之后 ⇒ 联合场已经在 sd 上，本段的划分作用在【并集场之上】）：
 *   A) HEADER 的 `layout(color) uniform half4 materialColor;` 行后 → 声明 `meldOwn` uniform；
 *   B) MAIN 的 `float coverage = clamp(0.5 - sd / lgFeather, 0.0, 1.0);` 行【之前】→ 归属划分。
 * 任一锚点未命中 ⇒ 打 Log.e（tag LGMeld）且该处不注入（绝不静默产出半截 shader）。
 *
 * 纪律：不使用 '%' 运算符、不使用 gl_FragCoord / resolution / time，只调用本工程既有函数
 * （sdShape）；不动折射/菲涅尔/高光/色散等任何光学参数（本文件只改「谁画哪个像素」）。
 *
 * 零回归保证：meldOwn 默认 0（未由 DualCardMeld 打开时）⇒ 本段整体不执行 ⇒ 逐像素等于改动前 ✓
 *   （单卡、两卡远离、dualCardMeld=false 三种情形全部覆盖 ✓）。
 */
object MeldShaders {

    private const val TAG = "LGMeld"

    /** 锚点 A：HEADER 里最后一条 uniform 声明（插入点紧随其后）。 */
    private const val UNIFORM_ANCHOR = "layout(color) uniform half4 materialColor;\n"

    /** 锚点 B：MAIN 里 coverage 的唯一定义行（本段插在它【之前】，因此作用在并集场之上）。 */
    private const val COVERAGE_ANCHOR =
        "    float coverage = clamp(0.5 - sd / lgFeather, 0.0, 1.0);"

    /** 新增 uniform 声明（默认 0 = 划分关闭 ⇒ 逐像素等于改动前）。 */
    val uniforms: String = "" +
        "// ---- 【P07·融合 meld】归属划分开关（1 = 并集区只由更近的那块卡绘制；0 = 不划分）----\n" +
        "uniform float meldOwn;\n"

    /** 归属划分片段（插在 coverage 行之前）。 */
    val fieldCode: String = "" +
        "    // ============ 【P07·融合 meld】并集「归属划分」============\n" +
        "    // 并集（smin(本卡, 对方卡) + 喉部）已在 sd 上；这里判「谁更近」，非归属方直接把 sd 顶到\n" +
        "    // 正无穷 ⇒ coverage=0 ⇒ 走下面的覆盖率早退（透明返回）⇒ 并集全图只被绘制一次。\n" +
        "    //   · 一次折射/一条轮廓/一条高光 ⇒ 重叠/相邻处【无双边框、无双亮带】✓（官方「共享采样区」）\n" +
        "    //   · 两侧输出在划分线上同源（同一个并集场 + 同一套光学）⇒ 划分线本身不可见 ✓\n" +
        "    //   · 门控两层：meldOwn（开关）· tintModel>0.5（只对卡片，面板/胶囊一律不受影响 ✓）—— 口径修订 2026-09-14：✗ 不再要求 max(fuseK,fuseNeckR)>0（叠放/贴合态融合权重被用户口径强制为 0，但并集仍要只画一次 ⇒ 划分必须在 k=0 时也能开；k=0 时 smin 严格退化 ⇒ sd=min(本卡,对方卡)=纯几何并集、无任何形变 ✓）\n" +
        "    if (meldOwn > 0.5 && tintModel > 0.5) {\n" +
        "        float2 lgMeldC2 = centered - fuseOffset2;\n" +
        "        float lgMeldSelf = sdShape(centered, shapeHalf, radius, shapeType);\n" +
        "        float lgMeldOther = sdShape(lgMeldC2, fuseHalf2, fuseRadius2, fuseShape2);\n" +
        "        if (lgMeldSelf > lgMeldOther) { sd = 1000000.0; }\n" +
        "    }\n"

    /**
     * 把归属划分片段注入到已有 AGSL 源码（两处锚点各命中一次）。
     *
     * 幂等性说明：只在 GlassShaders.source() 里对【刚拼好的】源码调用一次/档位；重复调用会因为锚点
     * 已被替换而命中失败（打 Log.e），因此绝不重复注入。
     */
    fun injectInto(src: String): String {
        val missing = ArrayList<String>(2)
        var out = src
        if (!out.contains(UNIFORM_ANCHOR)) {
            missing.add("uniform")
        } else {
            out = out.replace(UNIFORM_ANCHOR, UNIFORM_ANCHOR + uniforms)
        }
        if (!out.contains(COVERAGE_ANCHOR)) {
            missing.add("field")
        } else {
            out = out.replace(COVERAGE_ANCHOR, fieldCode + COVERAGE_ANCHOR)
        }
        if (missing.isEmpty()) {
            Log.i(
                TAG,
                "meld 归属划分 AGSL 注入 OK（uniform/field 两锚点全命中；len=" + out.length +
                    "，meldOwn 默认 0 ⇒ 未打开时行为与改动前逐像素一致）"
            )
        } else {
            Log.e(
                TAG,
                "meld 归属划分 AGSL 注入失败：未命中锚点 " + missing +
                    "（并集区会两卡各画一次 = 双边框 ✗；请检查 GlassShaders 的锚点行）"
            )
        }
        return out
    }
}
