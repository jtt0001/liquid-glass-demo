package com.example.liquidglass.diamond

/**
 * 【3D 钻石演示·光学内核】AGSL 源码管理与缓存 key。
 *
 * 与 [com.example.liquidglass.glass.GlassShaders] 同一套约定：
 *  · 源码按 [cacheKey] 缓存（底层 `runtimeShaderEffect(key, shaderString, uniformShaderName, block)`
 *    内部是 `getOrPut(key)` ⇒ 必须按切工给稳定且互不相同的 key，否则同屏多实例会互相覆写 uniform）；
 *  · 源码在【运行时着色器首次构建 / 首次绘制时】才编译 —— 任何语法错误都不会在构建期报出来，
 *    只会让打开钻石页的瞬间闪退。因此本文件的 AGSL 受静态约束自审（见 tools/verify_diamond_geometry.py
 *    的静态约束检查段）+ 几何离线校验（面表字面量 = 公式值）。
 *
 * uniform 名统一取自 [DiamondUniforms]（父会话用 `uniformShaderName = DiamondUniforms.INPUT` 绑定背景）。
 */
object DiamondAgsl {

    private const val KEY_PREFIX = "DiamondDemo"

    /** 缓存 key：`DiamondDemo_<CUT>_v1`（CUT = [DiamondCut.name]；参数变化不换 key）。 */
    fun cacheKey(cut: DiamondCut): String = KEY_PREFIX + "_" + cut.name + "_v1"

    /** 按 cut 缓存源码字符串（可重复调用：同一 cut 只拼一次、返回同一实例）。 */
    private val sourceCache: MutableMap<DiamondCut, String> =
        java.util.concurrent.ConcurrentHashMap<DiamondCut, String>()

    /** 完整 AGSL 源码（面表已按切工字面量展开；见 [AGSL_SOURCE]）。 */
    fun source(cut: DiamondCut): String {
        val hit: String? = sourceCache[cut]
        if (hit != null) { return hit }
        val built = buildSource(cut)
        sourceCache[cut] = built
        return built
    }

    /**
     * 拼装指定切工的源码。**不做任何会抛异常的强校验** —— 本函数在首次绘制时被调用，
     * 抛异常 = 打开页面闪退；自检请用 [selfCheck]（返回一行文本，供调试桥 / 日志消费）。
     */
    private fun buildSource(cut: DiamondCut): String {
        return when (cut) {
            DiamondCut.BRILLIANT17 -> AGSL_SOURCE
        }
    }

    /**
     * 自检一行（确定性、无副作用）；报告模型面数 / 面表行数 / 声明面数，
     * 便于 adb 侧直接确认"源码里的表 = 模型算出来的表"。
     */
    fun selfCheck(cut: DiamondCut): String {
        val modelFacets = DiamondModel.facets(cut).size / 4
        val tableLines = countFacetLines(AGSL_SOURCE)
        return "cut=" + cut.name + " modelFacets=" + modelFacets +
            " tableLines=" + tableLines + " declaredFacets=" + cut.facetCount
    }

    /** 数 AGSL 源码里机器可读面表行数（`// F <i> n=... d=...`）。 */
    private fun countFacetLines(src: String): Int {
        var n = 0
        for (line in src.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("// F ") && t.contains(" n=") && t.contains(" d=")) { n += 1 }
        }
        return n
    }

    /**
     * 完整 AGSL 源码（**面表以字面量完全展开**，无数组、无取模）。
     *
     * 机器可读面表行：`// F <i> n=<x> <y> <z> d=<d>`，由 tools/verify_diamond_geometry.py 解析，
     * 与几何公式推导值逐面比对（容差 1e-5）；同一脚本还会检查静态约束：
     * 无 % 运算符 / 无数组 / 无 gl_FragCoord / 无内置 uniform 名 / 循环只允许常量上界 for + break /
     * smoothstep(edge0, edge1, x) 的 edge0 < edge1 / AGSL uniform 名 = DiamondUniforms 常量。
     */
    private val AGSL_SOURCE: String = """
// ============================================================================
// DiamondAgsl —— 3D 钻石（简化明亮式切工 17 面）【物理正确实体折射】AGSL 内核
//
// 管线（严格按此顺序）：
//   ① coord → 主射线（世界坐标：相机 (0,0,uCamZ)，z=0 平面按"半径单位"恒等映射，焦距=uCamZ）
//   ② 世界 → 模型坐标（旋转的逆 = R^T；uRow0/1/2 是 R 的三行 ⇒ 与正向同一个表达式）
//   ③ 模型坐标轴对齐包围盒 early-out（盒外直接返回背景采样，不做任何追踪）
//   ④ 盒内 4 子样本命中测试 → coverage 在 0..1
// ⑤ coverage > 0 才做完整追踪：入面折射(Snell) → 内部 TIR（≤ uBounces 次弹射）
//      → 出射面再折射 → 与环境求交（背景平面 z=-uDepth；朝上时用它的镜像平面 z=+uDepth）
//      → 逆投影回屏幕坐标 → 采样背景（表面 Fresnel 反射项走同一套环境模型 ⇒ 只有表面是清晰镜像）
// ⑥ 颜色 = mix(本像素背景色, 追踪色, coverage 平滑过渡)（覆盖率的边缘过渡接进输出颜色）
//
// 硬约束（本项目 RuntimeShader 只在首次绘制时编译；语法错误 = 打开页面立刻闪退，构建期零提示）：
//   · 无取模运算符、无数组（面表完全展开为字面量）、无内置片段坐标变量
//   · uniform 名不用内置名（resolution / time / frame / date）
//   · 循环 = 常量上界 for + break
//   · 所有除法分母有正的下界；sqrt 参数 >= 0；clamp 到 -1..1；pow 底数 >= 0
//   · 任何退化/异常路径一律退化为"直接采样背景"（限幅 + 边缘延展）⇒ 绝不 NaN / 黑块
//
// 几何（模型半径 = 1，+z = 台面方向；八边形顶点角 22.5° + 45°k，k=0..7）：
//   腰棱环 r=1,z=0（本切工腰厚 0 = 锐边）；台面八边形外接半径 0.58 @ z=+0.288；尖底 (0,0,-0.86)
// ============================================================================

uniform shader content;     // 背景采样源（父会话用 drawBackdrop 的 uniformShaderName 绑定）
uniform float2 uRes;        // 图层尺寸 px
uniform float2 uCenter;     // 钻石中心（图层坐标 px）
uniform float uRadius;      // 腰棱半径 px
uniform float uCamZ;        // 相机距离（半径倍数；本项目 3.6）
uniform float uDepth;       // 背景平面深度（半径倍数；本项目 6.0）
uniform float3 uRow0;       // 旋转矩阵第 0 行（world = (dot(row0,v), dot(row1,v), dot(row2,v))）
uniform float3 uRow1;       // 第 1 行
uniform float3 uRow2;       // 第 2 行
uniform float uIor;         // 折射率（默认 2.417）
uniform float uDispersion;  // 色散强度（0 = 关；>0 时 RGB 三条射线）
uniform float uBounces;     // 内部弹射上限 0..4（0 = 单次折射、内部不弹射）
uniform float uDebug;       // 0=FINAL 1=面法线 2=面 ID 3=仅折射 4=背景直通
uniform float uAA;          // 边缘 AA 宽度 px
uniform float uFresnel;     // Fresnel 反射项强度 0..1
uniform float uEnvModel;    // 环境采样模型：1=几何一致(默认) 0=旧「方向×0.5」软投影回退
uniform float uTrappedFix;  // 【弹射用尽物理收尾】1=补链 + 真界面透射率(默认) 0=旧 0.5 经验衰减
uniform float uEnvYFix;     // 【逆投影 y 符号】1=世界点→屏幕的正确投影(默认) 0=H1 原样(y 取反、采样点上下镜像)

// ---- 面表（17 面；法线朝外、单位长；内部 = dot(n,p) <= d）---------------------
// 台面 1 面 + 冠部 8 面（k=0..7）+ 亭部 8 面（k=0..7），与 DiamondModel.facets 同式：
//   冠部 k：过腰棱边 (v_k → v_(k+1))(z=0) 与台面边 (t_k → t_(k+1))(z=0.288) 的平面
//   亭部 k：过腰棱边 (v_k → v_(k+1))(z=0) 与尖底 (0,0,-0.86) 的平面
// 下面每行是机器可读格式，tools/verify_diamond_geometry.py 解析并与几何公式逐面比对（容差 1e-5）：
// F 0 n=0.0 0.0 1.0 d=0.288
// F 1 n=0.421429 0.421429 0.802992 d=0.550623
// F 2 n=0.0 0.59599 0.802992 d=0.550623
// F 3 n=-0.421429 0.421429 0.802992 d=0.550623
// F 4 n=-0.59599 0.0 0.802992 d=0.550623
// F 5 n=-0.421429 -0.421429 0.802992 d=0.550623
// F 6 n=0.0 -0.59599 0.802992 d=0.550623
// F 7 n=0.421429 -0.421429 0.802992 d=0.550623
// F 8 n=0.59599 0.0 0.802992 d=0.550623
// F 9 n=0.481787 0.481787 -0.731959 d=0.629484
// F 10 n=0.0 0.681349 -0.731959 d=0.629484
// F 11 n=-0.481787 0.481787 -0.731959 d=0.629484
// F 12 n=-0.681349 0.0 -0.731959 d=0.629484
// F 13 n=-0.481787 -0.481787 -0.731959 d=0.629484
// F 14 n=0.0 -0.681349 -0.731959 d=0.629484
// F 15 n=0.481787 -0.481787 -0.731959 d=0.629484
// F 16 n=0.681349 0.0 -0.731959 d=0.629484

// ---- 世界 ←→ 模型：R 正交 ⇒ 同一个表达式既是 world→model（= R^T v）也是 model→world（= R v）
float3 rotRow(float3 v) {
    return float3(dot(uRow0, v), dot(uRow1, v), dot(uRow2, v));
}

// ---- 安全采样：限幅到图层内 + 边缘延展（clamp 到边界像素复制）⇒ 绝不透明 / 黑边
half4 safeContent(float2 c) {
    float2 lo = float2(0.5, 0.5);
    float2 hi = max(uRes - lo, lo);          // 上界不小于下界（uRes 异常时也不产生错序边界）
    return content.eval(min(max(c, lo), hi));   // 等价 clamp，但只用 max/min（向量版最保险）
}

// ---- 逐轴防零：|v| < 1e-6 时替换成带符号的 1e-6（保证除法分母非零 ⇒ 无 inf/NaN）
float slabSafe(float v) {
    if (v > 1.0e-6) { return v; }
    if (v < -1.0e-6) { return v; }
    if (v >= 0.0) { return 1.0e-6; }
    return -1.0e-6;
}

// ---- 入向平面候选：dot(n,rd) < 0 时给 t = (d - dot(n,ro)) / dot(n,rd)，否则 -1e9（不构成下界）
//      内部 = dot(n,p) <= d ⇒ 沿 rd 前进时约束为 t >= t_i，入点 = 所有入向平面 t_i 的最大值
float enterCand(float3 ro, float3 rd, float3 n, float df) {
    float dn = dot(n, rd);
    if (dn > -1.0e-6) { return -1.0e9; }
    return (df - dot(n, ro)) / dn;           // |dn| >= 1e-6 ⇒ 分母非零
}

// ---- 出向平面候选：dot(n,rd) > 0 时给 t，否则 +1e9（出点 = 所有出向平面 t_i 的最小值）
float exitCand(float3 ro, float3 rd, float3 n, float df) {
    float dn = dot(n, rd);
    if (dn < 1.0e-6) { return 1.0e9; }
    return (df - dot(n, ro)) / dn;           // 同上：分母非零
}

// ---- 命中测试（只判"是否命中实体"，供 coverage 用）
//      凸体 = 17 个半空间之交 ⇒ 命中 ⇔ max(入向 t) <= min(出向 t) 且出向 t > 0
float bodyHit(float3 ro, float3 rd) {
    float tIn = -1.0e9;
    tIn = max(tIn, enterCand(ro, rd, float3(0.0, 0.0, 1.0), 0.288));   // code-facet 0
    tIn = max(tIn, enterCand(ro, rd, float3(0.421429, 0.421429, 0.802992), 0.550623));   // code-facet 1
    tIn = max(tIn, enterCand(ro, rd, float3(0.0, 0.59599, 0.802992), 0.550623));   // code-facet 2
    tIn = max(tIn, enterCand(ro, rd, float3(-0.421429, 0.421429, 0.802992), 0.550623));   // code-facet 3
    tIn = max(tIn, enterCand(ro, rd, float3(-0.59599, 0.0, 0.802992), 0.550623));   // code-facet 4
    tIn = max(tIn, enterCand(ro, rd, float3(-0.421429, -0.421429, 0.802992), 0.550623));   // code-facet 5
    tIn = max(tIn, enterCand(ro, rd, float3(0.0, -0.59599, 0.802992), 0.550623));   // code-facet 6
    tIn = max(tIn, enterCand(ro, rd, float3(0.421429, -0.421429, 0.802992), 0.550623));   // code-facet 7
    tIn = max(tIn, enterCand(ro, rd, float3(0.59599, 0.0, 0.802992), 0.550623));   // code-facet 8
    tIn = max(tIn, enterCand(ro, rd, float3(0.481787, 0.481787, -0.731959), 0.629484));   // code-facet 9
    tIn = max(tIn, enterCand(ro, rd, float3(0.0, 0.681349, -0.731959), 0.629484));   // code-facet 10
    tIn = max(tIn, enterCand(ro, rd, float3(-0.481787, 0.481787, -0.731959), 0.629484));   // code-facet 11
    tIn = max(tIn, enterCand(ro, rd, float3(-0.681349, 0.0, -0.731959), 0.629484));   // code-facet 12
    tIn = max(tIn, enterCand(ro, rd, float3(-0.481787, -0.481787, -0.731959), 0.629484));   // code-facet 13
    tIn = max(tIn, enterCand(ro, rd, float3(0.0, -0.681349, -0.731959), 0.629484));   // code-facet 14
    tIn = max(tIn, enterCand(ro, rd, float3(0.481787, -0.481787, -0.731959), 0.629484));   // code-facet 15
    tIn = max(tIn, enterCand(ro, rd, float3(0.681349, 0.0, -0.731959), 0.629484));   // code-facet 16
    float tOut = 1.0e9;
    tOut = min(tOut, exitCand(ro, rd, float3(0.0, 0.0, 1.0), 0.288));   // code-facet 0
    tOut = min(tOut, exitCand(ro, rd, float3(0.421429, 0.421429, 0.802992), 0.550623));   // code-facet 1
    tOut = min(tOut, exitCand(ro, rd, float3(0.0, 0.59599, 0.802992), 0.550623));   // code-facet 2
    tOut = min(tOut, exitCand(ro, rd, float3(-0.421429, 0.421429, 0.802992), 0.550623));   // code-facet 3
    tOut = min(tOut, exitCand(ro, rd, float3(-0.59599, 0.0, 0.802992), 0.550623));   // code-facet 4
    tOut = min(tOut, exitCand(ro, rd, float3(-0.421429, -0.421429, 0.802992), 0.550623));   // code-facet 5
    tOut = min(tOut, exitCand(ro, rd, float3(0.0, -0.59599, 0.802992), 0.550623));   // code-facet 6
    tOut = min(tOut, exitCand(ro, rd, float3(0.421429, -0.421429, 0.802992), 0.550623));   // code-facet 7
    tOut = min(tOut, exitCand(ro, rd, float3(0.59599, 0.0, 0.802992), 0.550623));   // code-facet 8
    tOut = min(tOut, exitCand(ro, rd, float3(0.481787, 0.481787, -0.731959), 0.629484));   // code-facet 9
    tOut = min(tOut, exitCand(ro, rd, float3(0.0, 0.681349, -0.731959), 0.629484));   // code-facet 10
    tOut = min(tOut, exitCand(ro, rd, float3(-0.481787, 0.481787, -0.731959), 0.629484));   // code-facet 11
    tOut = min(tOut, exitCand(ro, rd, float3(-0.681349, 0.0, -0.731959), 0.629484));   // code-facet 12
    tOut = min(tOut, exitCand(ro, rd, float3(-0.481787, -0.481787, -0.731959), 0.629484));   // code-facet 13
    tOut = min(tOut, exitCand(ro, rd, float3(0.0, -0.681349, -0.731959), 0.629484));   // code-facet 14
    tOut = min(tOut, exitCand(ro, rd, float3(0.481787, -0.481787, -0.731959), 0.629484));   // code-facet 15
    tOut = min(tOut, exitCand(ro, rd, float3(0.681349, 0.0, -0.731959), 0.629484));   // code-facet 16
    if (tIn > tOut) { return 0.0; }
    if (tOut <= 0.0) { return 0.0; }
    return 1.0;
}

// ---- 出射面求交（内部起点 ⇒ 最近的外向平面）：返回 float4(t, nx, ny, nz)
//      t = 1e9 表示无出射面（异常，理论不可达；调用方必须按"退化"处理）
float4 exitHit(float3 ro, float3 rd) {
    float bt = 1.0e9;
    float3 bn = float3(0.0, 0.0, 1.0);
    float c0 = exitCand(ro, rd, float3(0.0, 0.0, 1.0), 0.288); if (c0 < bt) { bt = c0; bn = float3(0.0, 0.0, 1.0); }   // code-facet 0
    float c1 = exitCand(ro, rd, float3(0.421429, 0.421429, 0.802992), 0.550623); if (c1 < bt) { bt = c1; bn = float3(0.421429, 0.421429, 0.802992); }   // code-facet 1
    float c2 = exitCand(ro, rd, float3(0.0, 0.59599, 0.802992), 0.550623); if (c2 < bt) { bt = c2; bn = float3(0.0, 0.59599, 0.802992); }   // code-facet 2
    float c3 = exitCand(ro, rd, float3(-0.421429, 0.421429, 0.802992), 0.550623); if (c3 < bt) { bt = c3; bn = float3(-0.421429, 0.421429, 0.802992); }   // code-facet 3
    float c4 = exitCand(ro, rd, float3(-0.59599, 0.0, 0.802992), 0.550623); if (c4 < bt) { bt = c4; bn = float3(-0.59599, 0.0, 0.802992); }   // code-facet 4
    float c5 = exitCand(ro, rd, float3(-0.421429, -0.421429, 0.802992), 0.550623); if (c5 < bt) { bt = c5; bn = float3(-0.421429, -0.421429, 0.802992); }   // code-facet 5
    float c6 = exitCand(ro, rd, float3(0.0, -0.59599, 0.802992), 0.550623); if (c6 < bt) { bt = c6; bn = float3(0.0, -0.59599, 0.802992); }   // code-facet 6
    float c7 = exitCand(ro, rd, float3(0.421429, -0.421429, 0.802992), 0.550623); if (c7 < bt) { bt = c7; bn = float3(0.421429, -0.421429, 0.802992); }   // code-facet 7
    float c8 = exitCand(ro, rd, float3(0.59599, 0.0, 0.802992), 0.550623); if (c8 < bt) { bt = c8; bn = float3(0.59599, 0.0, 0.802992); }   // code-facet 8
    float c9 = exitCand(ro, rd, float3(0.481787, 0.481787, -0.731959), 0.629484); if (c9 < bt) { bt = c9; bn = float3(0.481787, 0.481787, -0.731959); }   // code-facet 9
    float c10 = exitCand(ro, rd, float3(0.0, 0.681349, -0.731959), 0.629484); if (c10 < bt) { bt = c10; bn = float3(0.0, 0.681349, -0.731959); }   // code-facet 10
    float c11 = exitCand(ro, rd, float3(-0.481787, 0.481787, -0.731959), 0.629484); if (c11 < bt) { bt = c11; bn = float3(-0.481787, 0.481787, -0.731959); }   // code-facet 11
    float c12 = exitCand(ro, rd, float3(-0.681349, 0.0, -0.731959), 0.629484); if (c12 < bt) { bt = c12; bn = float3(-0.681349, 0.0, -0.731959); }   // code-facet 12
    float c13 = exitCand(ro, rd, float3(-0.481787, -0.481787, -0.731959), 0.629484); if (c13 < bt) { bt = c13; bn = float3(-0.481787, -0.481787, -0.731959); }   // code-facet 13
    float c14 = exitCand(ro, rd, float3(0.0, -0.681349, -0.731959), 0.629484); if (c14 < bt) { bt = c14; bn = float3(0.0, -0.681349, -0.731959); }   // code-facet 14
    float c15 = exitCand(ro, rd, float3(0.481787, -0.481787, -0.731959), 0.629484); if (c15 < bt) { bt = c15; bn = float3(0.481787, -0.481787, -0.731959); }   // code-facet 15
    float c16 = exitCand(ro, rd, float3(0.681349, 0.0, -0.731959), 0.629484); if (c16 < bt) { bt = c16; bn = float3(0.681349, 0.0, -0.731959); }   // code-facet 16
    return float4(bt, bn.x, bn.y, bn.z);
}

// ---- 入面求交（凸体：入向平面中 t 最大者）+ 命中校验（须不晚于最近的出向平面）
//      返回 float4(t, nx, ny, nz)；未命中返回 t = -1.0（< 0 即未命中）
float4 enterHit(float3 ro, float3 rd) {
    float bt = -1.0e9;
    float3 bn = float3(0.0, 0.0, 1.0);
    float c0 = enterCand(ro, rd, float3(0.0, 0.0, 1.0), 0.288); if (c0 > bt) { bt = c0; bn = float3(0.0, 0.0, 1.0); }   // code-facet 0
    float c1 = enterCand(ro, rd, float3(0.421429, 0.421429, 0.802992), 0.550623); if (c1 > bt) { bt = c1; bn = float3(0.421429, 0.421429, 0.802992); }   // code-facet 1
    float c2 = enterCand(ro, rd, float3(0.0, 0.59599, 0.802992), 0.550623); if (c2 > bt) { bt = c2; bn = float3(0.0, 0.59599, 0.802992); }   // code-facet 2
    float c3 = enterCand(ro, rd, float3(-0.421429, 0.421429, 0.802992), 0.550623); if (c3 > bt) { bt = c3; bn = float3(-0.421429, 0.421429, 0.802992); }   // code-facet 3
    float c4 = enterCand(ro, rd, float3(-0.59599, 0.0, 0.802992), 0.550623); if (c4 > bt) { bt = c4; bn = float3(-0.59599, 0.0, 0.802992); }   // code-facet 4
    float c5 = enterCand(ro, rd, float3(-0.421429, -0.421429, 0.802992), 0.550623); if (c5 > bt) { bt = c5; bn = float3(-0.421429, -0.421429, 0.802992); }   // code-facet 5
    float c6 = enterCand(ro, rd, float3(0.0, -0.59599, 0.802992), 0.550623); if (c6 > bt) { bt = c6; bn = float3(0.0, -0.59599, 0.802992); }   // code-facet 6
    float c7 = enterCand(ro, rd, float3(0.421429, -0.421429, 0.802992), 0.550623); if (c7 > bt) { bt = c7; bn = float3(0.421429, -0.421429, 0.802992); }   // code-facet 7
    float c8 = enterCand(ro, rd, float3(0.59599, 0.0, 0.802992), 0.550623); if (c8 > bt) { bt = c8; bn = float3(0.59599, 0.0, 0.802992); }   // code-facet 8
    float c9 = enterCand(ro, rd, float3(0.481787, 0.481787, -0.731959), 0.629484); if (c9 > bt) { bt = c9; bn = float3(0.481787, 0.481787, -0.731959); }   // code-facet 9
    float c10 = enterCand(ro, rd, float3(0.0, 0.681349, -0.731959), 0.629484); if (c10 > bt) { bt = c10; bn = float3(0.0, 0.681349, -0.731959); }   // code-facet 10
    float c11 = enterCand(ro, rd, float3(-0.481787, 0.481787, -0.731959), 0.629484); if (c11 > bt) { bt = c11; bn = float3(-0.481787, 0.481787, -0.731959); }   // code-facet 11
    float c12 = enterCand(ro, rd, float3(-0.681349, 0.0, -0.731959), 0.629484); if (c12 > bt) { bt = c12; bn = float3(-0.681349, 0.0, -0.731959); }   // code-facet 12
    float c13 = enterCand(ro, rd, float3(-0.481787, -0.481787, -0.731959), 0.629484); if (c13 > bt) { bt = c13; bn = float3(-0.481787, -0.481787, -0.731959); }   // code-facet 13
    float c14 = enterCand(ro, rd, float3(0.0, -0.681349, -0.731959), 0.629484); if (c14 > bt) { bt = c14; bn = float3(0.0, -0.681349, -0.731959); }   // code-facet 14
    float c15 = enterCand(ro, rd, float3(0.481787, -0.481787, -0.731959), 0.629484); if (c15 > bt) { bt = c15; bn = float3(0.481787, -0.481787, -0.731959); }   // code-facet 15
    float c16 = enterCand(ro, rd, float3(0.681349, 0.0, -0.731959), 0.629484); if (c16 > bt) { bt = c16; bn = float3(0.681349, 0.0, -0.731959); }   // code-facet 16
    float tOut = exitHit(ro, rd).x;
    if (bt > tOut) { return float4(-1.0, 0.0, 0.0, 1.0); }
    if (tOut <= 0.0) { return float4(-1.0, 0.0, 0.0, 1.0); }
    return float4(bt, bn.x, bn.y, bn.z);
}

// ---- 面 ID（debug=2）：按法线比对（阈值比浮点相等稳）；未匹配返回 17.0
float facetIdOf(float3 n) {
    if (dot(n, float3(0.0, 0.0, 1.0)) > 0.9995) { return 0.0; }   // code-facet 0
    if (dot(n, float3(0.421429, 0.421429, 0.802992)) > 0.9995) { return 1.0; }   // code-facet 1
    if (dot(n, float3(0.0, 0.59599, 0.802992)) > 0.9995) { return 2.0; }   // code-facet 2
    if (dot(n, float3(-0.421429, 0.421429, 0.802992)) > 0.9995) { return 3.0; }   // code-facet 3
    if (dot(n, float3(-0.59599, 0.0, 0.802992)) > 0.9995) { return 4.0; }   // code-facet 4
    if (dot(n, float3(-0.421429, -0.421429, 0.802992)) > 0.9995) { return 5.0; }   // code-facet 5
    if (dot(n, float3(0.0, -0.59599, 0.802992)) > 0.9995) { return 6.0; }   // code-facet 6
    if (dot(n, float3(0.421429, -0.421429, 0.802992)) > 0.9995) { return 7.0; }   // code-facet 7
    if (dot(n, float3(0.59599, 0.0, 0.802992)) > 0.9995) { return 8.0; }   // code-facet 8
    if (dot(n, float3(0.481787, 0.481787, -0.731959)) > 0.9995) { return 9.0; }   // code-facet 9
    if (dot(n, float3(0.0, 0.681349, -0.731959)) > 0.9995) { return 10.0; }   // code-facet 10
    if (dot(n, float3(-0.481787, 0.481787, -0.731959)) > 0.9995) { return 11.0; }   // code-facet 11
    if (dot(n, float3(-0.681349, 0.0, -0.731959)) > 0.9995) { return 12.0; }   // code-facet 12
    if (dot(n, float3(-0.481787, -0.481787, -0.731959)) > 0.9995) { return 13.0; }   // code-facet 13
    if (dot(n, float3(0.0, -0.681349, -0.731959)) > 0.9995) { return 14.0; }   // code-facet 14
    if (dot(n, float3(0.481787, -0.481787, -0.731959)) > 0.9995) { return 15.0; }   // code-facet 15
    if (dot(n, float3(0.681349, 0.0, -0.731959)) > 0.9995) { return 16.0; }   // code-facet 16
    return 17.0;
}

// ---- 自己实现 Snell 折射（不假定内置 refract 可用）
//      k = 1 - eta*eta*(1 - cosi*cosi)；k < 0 ⇒ 全反射（走反射分支，flag = 0，绝不 NaN）
//      返回 float4(dir.xyz, flag)：flag = 1 折射成功 / 0 全反射
float4 refractOrReflect(float3 L, float3 nOut, float eta) {
    float dnl = dot(nOut, L);
    float3 N = nOut;                          // 统一成"朝向入射侧"的法线（dot(N,L) <= 0）
    if (dnl > 0.0) { N = -nOut; }
    float ci = clamp(-dot(N, L), 0.0, 1.0);   // 入射角余弦，clamp 到 0..1
    float k = 1.0 - eta * eta * (1.0 - ci * ci);
    if (k < 0.0) {                            // 全反射（不产生 NaN：不做 sqrt）
        float3 R = L - 2.0 * dnl * nOut;
        return float4(normalize(R).x, normalize(R).y, normalize(R).z, 0.0);
    }
    float s = sqrt(max(k, 0.0));              // k >= 0 才 sqrt
    float3 T = eta * L + (eta * ci - s) * N;
    T = normalize(T);
    return float4(T.x, T.y, T.z, 1.0);
}

// ---- 软投影采样坐标：出射方向 → 图层 px（有界、连续、绝不飞出图层 ⇒ 边缘延展）
//      【仅作 uEnvModel = 0 的回退分支】与场景几何无关，属 744aef1 的旧行为
float2 bgCoordSoft(float3 dW) {
    return uCenter + float2(dW.x, -dW.y) * 0.5 * uRadius;
}

// ---- 【修复 H1】几何一致环境采样：背景平面 + 它关于 z = 0 的镜像平面 ----------------
//  朝下（dW.z < 0）⇒ 与 z = -uDepth 求交；朝上（dW.z >= 0）⇒ 与镜像平面 z = +uDepth 求交
//  （等价于把射线关于 z = 0 镜像后再求交）。两个分支共用同一个针孔逆投影：
//      世界点 P → 屏幕（半径单位）= P.xy · uCamZ/(uCamZ + uDepth)   相机 (0,0,uCamZ)、胶片 z=0
//  语义：表面 Fresnel 反射 = 背景平面的**真实镜像成像**（唯一合法的"镜面"）；
//        内部 TIR 回光 = 从场景另一侧看到同一张背景（折回光路，见 tools/diamond_refraction_research.md §3）。
//  ⇒ 不再出现旧实现的"方向×0.5 位移"（与场景尺度无关）——那会让面朝上姿态下全部出射像素
//    都把采样点落回宝石脚下那一小片最平滑的背景上（实测中位 79px = 0.22 半径），
//    整块宝石因此呈现"内部自己在一块平色玻璃里反射"的假象（见 tools/diamond_refraction_audit.py §B/⑥）。
float2 bgCoordEnv(float3 pW, float3 dW) {
    float sgn = 1.0;                                         // 朝下 +1 / 朝上 -1
    if (dW.z >= 0.0) { sgn = -1.0; }
    float zPlane = -uDepth * sgn;                            // z = -uDepth 或 z = +uDepth
    float dz = dW.z;
    if (dz > -0.08 && dz < 0.08) {                           // 掠射保护：|分母| >= 0.08
        dz = -0.08;
        if (dW.z >= 0.0) { dz = 0.08; }
    }
    float t = (zPlane - pW.z) / dz;
    t = clamp(t, 0.0, 4.0 * (uCamZ + uDepth));               // 有界：掠射时不炸（同旧实现的上界）
    float3 pBg = pW + t * dW;
    // ---- 【H4 逆投影 y 符号】世界点 P 落在屏幕上的像素 = (CX + P.x·k·R, CY − P.y·k·R)，
    //  其中 k = uCamZ/(uCamZ+uDepth)：这是 primaryDir 的【逆映射】（primaryDir 里 sc.y = (CY − py)/R
    //  ⇒ py = CY − sc.y·R = CY − P.y·k·R）——也就是本项目"背景图 = 屏幕空间背景照片"这一前提的**定义**。
    //  旧 bgCoordExit 的平面支路与 bgCoordSoft 都是这个符号（744aef1 口径）；H1 重写时 y 被双重取反
    //  （float2(pBg.x, -pBg.y) 又乘 float2(s.x, -s.y)）⇒ 采样点关于屏幕水平中线镜像 = 内部内容上下颠倒。
    //  · uEnvYFix = 1（默认）：用正确投影（y 不取反）。
    //  · uEnvYFix = 0：逐字回到 H1 原样（仅用于 A/B 对照；✗ 不是"旧软投影"那条被否定的分支）。
    float3 pS = pBg;
    if (uEnvYFix < 0.5) { pS.y = -pBg.y; }
    float2 s = pS.xy * (uCamZ / (uCamZ + uDepth));           // 半径单位屏幕坐标（y 向上）
    // ---- 只对【朝上/回光】支路做保向有界压缩（朝下支路保持精确逆投影，见上） ------------
    //  为什么：本场景在相机一侧没有几何体，朝上射线的严格解 = 与镜像平面交点（可能 10+ 半径），
    //  直接采样必然跑出图层 ⇒ 边缘延展 ⇒ 刻面里出现"百叶窗"状条带（实测约四成样本出屏）。
    //  这里把归一化坐标 u = s/half 平滑压成 u/(1+|u|)：单调、保向、|u'| < 1 恒在屏内（不再触发边缘延展），
    //  且 r→0 时 ≈ 恒等（近场/表面镜面几乎不受影响）。物理上等价于"把相机一侧的环境当成一张包围宝石的
    //  照片穹顶"（屏幕空间环境探针的常规做法），与背后的精确平面逆投影并存。
    if (dW.z >= 0.0) {
        float2 h = max(uRes * 0.5 / max(uRadius, 1.0) - 0.15, float2(0.30, 0.30));
        float2 u = s / h;
        s = h * u / (1.0 + length(u));
    }
    return uCenter + float2(s.x, -s.y) * uRadius;             // 半径单位 → 图层 px
}

// ---- 旧版 bgCoordExit（朝下平面逆投影 / 朝上软投影 + 按 wDown 混合）—— 仅 uEnvModel = 0 时使用
float2 bgCoordExit(float3 pW, float3 dW) {
    float tDen = min(dW.z, -0.08);
    float tBg = (-uDepth - pW.z) / tDen;
    tBg = clamp(tBg, 0.0, 4.0 * (uCamZ + uDepth));
    float3 pBg = pW + tBg * dW;
    float2 sPlane = pBg.xy * (uCamZ / (uCamZ + uDepth));
    float2 sSoft = float2(dW.x, -dW.y) * 0.5;
    float wDown = smoothstep(0.02, 0.30, -dW.z);
    float2 s = mix(sSoft, sPlane, wDown);
    return uCenter + float2(s.x, -s.y) * uRadius;
}

// ---- 完整追踪一条射线并返回采样色（float3，已含 Fresnel 反射项）
//      任何异常（未命中 / 入面全反射 / 无出射面 / 弹射预算用尽）都退化为"直接采样背景"
//      【H2 能量】uEnvModel=1 时：透射路径按 出射界面透射率 (1-R_内) 加权
//      —— 物理口径：内部界面**不是**额外的可见镜面；清晰镜像只来自表面那一次 Fresnel 反射。
//      【H3 弹射用尽·物理收尾】uTrappedFix=1（默认）时：
//        · 补链：预算放宽到 uBounces + min(uBounces, 6) —— 弹射上限是"质量/性能档位"，
//          不是"物理上限"；用尽就让光路继续走到真出射面，别在预算边界上凭空切一刀
//          （切一刀 = 相邻像素一个"出射"、一个"用尽"，亮度台阶 (1-R)-0.5 ≈ 0.33 ⇒ 正面看就是
//          一条横贯宝石的硬边带 = 用户报的"内部三层横向折射"）。
//        · 收尾能量：真的走不出去（uBounces=0 或真陷光）时，TIR 无损 ⇒ 唯一损耗在最终出射界面，
//          所以用最后界面的 (1-R) 而不是拍脑袋的 0.5（0.5 会在边界上留下同样的硬台阶）。
//        · uTrappedFix=0 ⇒ 逐字回到旧口径（预算 = uBounces、用尽 ⇒ 0.5），一行可回退。
float3 traceColor(float3 ro, float3 rd, float iorK, float2 coord, float fresK) {
    float3 fallback = float3(safeContent(coord).rgb);
    float4 e = enterHit(ro, rd);
    if (e.x < 0.0) { return fallback; }                        // 异常①：未命中实体
    float3 nE = e.yzw;
    float4 r0 = refractOrReflect(rd, nE, 1.0 / iorK);          // 入面折射：eta = n1/n2 = 1/ior
    if (r0.w < 0.5) { return fallback; }                       // 异常②：入面全反射（ior>1 时不会发生）
    float3 d = r0.xyz;
    float3 p = ro + e.x * rd + d * 1.0e-3;                     // 入点 + 微推进（避免与入面自交）
    float f0a = (1.0 - iorK) / (1.0 + iorK);                   // F0 = ((n1-n2)/(n1+n2))^2（正入射，双向对称）
    float f0 = f0a * f0a;
    float ok = 0.0;
    float transK = 1.0;                                        // 透射路径能量（H2；回退模式恒为 1）
    float3 exitDir = d;
    float3 exitPos = p;
    // ---- H3：预算与补链（uTrappedFix=1 ⇒ 额外预算 = min(uBounces, 6)；=0 ⇒ 额外预算 0 = 旧口径）
    float extra = 0.0;
    if (uTrappedFix > 0.5) { extra = clamp(uBounces, 0.0, 6.0); }
    float limit = uBounces + extra;
    float lastCi = 0.0;                                        // 最后一次内部全反射界面的"自内向外入射角余弦"
    for (int i = 0; i < 11; i++) {                             // 常量上界 for（<= 4 + 6 次弹射 + 出射尝试）
        if (float(i) > limit) { break; }                       // 预算（用户档位 + 补链）用尽
        float4 h = exitHit(p, d);
        if (h.x > 1.0e8) { break; }                            // 异常③：无出射面
        float3 ph = p + h.x * d;
        float4 rr = refractOrReflect(d, h.yzw, iorK);          // 出射折射：eta = ior/1（自内向外）
        if (rr.w > 0.5) {                                      // 折射出射 ⇒ 拿到出射面
            exitDir = rr.xyz; exitPos = ph; ok = 1.0;
            if (uEnvModel > 0.5) {
                // 【H2】出射界面透射率 1-R(θ)：内部界面不是额外的镜面，只按 Fresnel 衰减这条透射路径
                float ciE = clamp(dot(h.yzw, d), 0.0, 1.0);     // 自内向外的入射角余弦（h.yzw 朝外 ⇒ 点乘 > 0）
                transK = 1.0 - (f0 + (1.0 - f0) * pow(clamp(1.0 - ciE, 0.0, 1.0), 5.0));
            }
            break;
        }
        lastCi = clamp(dot(h.yzw, d), 0.0, 1.0);               // 记录本界面（H3 收尾能量用）
        if (float(i) >= limit) { break; }                      // 预算用尽 ⇒ 内部不再弹射
        d = rr.xyz;                                            // 记一次内部全反射（总数 <= limit），继续弹射
        p = ph + d * 1.0e-3;
        exitDir = d;
        exitPos = p;
    }
    if (uEnvModel > 0.5 && ok < 0.5) {
        if (uTrappedFix > 0.5 && extra > 0.5) {
            // 【H3】物理收尾：TIR 无损 ⇒ 唯一损耗在最终出射界面 ⇒ 用最后界面的 (1-R)
            //（不再用 0.5 —— 那会在"用尽/出射"边界上留一条 ~0.33 的亮度硬台阶）
            transK = 1.0 - (f0 + (1.0 - f0) * pow(clamp(1.0 - lastCi, 0.0, 1.0), 5.0));
        } else {
            transK = 0.5;                                      // 旧口径（uTrappedFix=0 / uBounces=0）：经验衰减
        }
    }
    // 出射点/方向 → 世界 → 背景采样（uEnvModel=1：几何一致环境；=0：旧软投影）
    float3 pW = rotRow(exitPos);
    float3 dW = rotRow(exitDir);
    float2 bgc = bgCoordSoft(dW);                              // 回退模式 / 旧行为的退化分支
    if (uEnvModel > 0.5) {
        bgc = bgCoordEnv(pW, dW);                              // 新：几何一致（退化时也用最后一段内部光线）
    } else if (ok > 0.5) {
        bgc = bgCoordExit(pW, dW);                             // 旧：朝下平面逆投影 / 朝上软投影
    }
    float3 col = float3(safeContent(bgc).rgb) * transK;
    // ---- 入面 Fresnel 反射项（Schlick 近似）：F0 = ((1-ior)/(1+ior))^2（ior > 1 ⇒ 分母非零）
    float cosi = clamp(-dot(rd, nE), 0.0, 1.0);
    float omc = clamp(1.0 - cosi, 0.0, 1.0);
    float fr = f0 + (1.0 - f0) * pow(omc, 5.0);                // 底数 >= 0
    float kF = clamp(fr * fresK, 0.0, 1.0);
    if (kF > 0.002) {
        float3 pSurf = rotRow(ro + e.x * rd);                  // 表面点（世界）—— 表面反射的镜像要按它求交
        float3 rW = rotRow(rd);
        float3 nW = rotRow(nE);
        float3 rf = rW - 2.0 * dot(rW, nW) * nW;               // 反射方向（世界）
        float2 scSurf = bgCoordSoft(rf);
        if (uEnvModel > 0.5) { scSurf = bgCoordEnv(pSurf, rf); }
        col = mix(col, float3(safeContent(scSurf).rgb), kF);   // 表面 = 唯一合法的"清晰镜像"
    }
    return col;
}

// ---- 屏幕 px → 主射线方向（模型坐标）。屏幕 px → "半径单位"坐标采用标准相机约定：
//      模型 +y = 屏幕向上 ⇒ 像素 y 取反；z=0 平面按恒等映射（焦距 = uCamZ）。
//      （若 UI 侧需要相反手感，只需把下面的 (uCenter.y - px.y) 改成 (px.y - uCenter.y)）
float3 primaryDir(float2 px) {
    float2 sc = float2((px.x - uCenter.x) / max(uRadius, 1.0), (uCenter.y - px.y) / max(uRadius, 1.0));
    return rotRow(normalize(float3(sc.x, sc.y, -uCamZ)));
}

half4 main(float2 coord) {
    // ---- debug=4：背景直通（阳性对照臂）
    if (uDebug > 3.5) { return safeContent(coord); }

    // ---- ① 主射线（世界坐标）→ ② 模型坐标
    float3 camW = float3(0.0, 0.0, uCamZ);
    float3 ro = rotRow(camW);
    float3 rd = primaryDir(coord);

    // ---- ③ 包围盒 early-out（模型坐标轴对齐盒：腰棱 ±1、台面 z=0.288、尖底 z=-0.86，各留 0.02 余量）
    float3 bmin = float3(-1.02, -1.02, -0.88);
    float3 bmax = float3(1.02, 1.02, 0.30);
    float3 ds = float3(slabSafe(rd.x), slabSafe(rd.y), slabSafe(rd.z));
    float3 ta = (bmin - ro) / ds;
    float3 tb = (bmax - ro) / ds;
    float tbNear = max(max(min(ta.x, tb.x), min(ta.y, tb.y)), min(ta.z, tb.z));
    float tbFar = min(min(max(ta.x, tb.x), max(ta.y, tb.y)), max(ta.z, tb.z));
    if (tbNear > tbFar) { return safeContent(coord); }         // 盒外：直接背景，不做任何追踪
    if (tbFar <= 0.0) { return safeContent(coord); }

    // ---- ④ 4 子样本命中测试 → coverage（半宽 = uAA/4 px；子样本偏移后各自成射线）
    float q = clamp(uAA, 0.5, 4.0) * 0.25;
    float hits = bodyHit(ro, primaryDir(coord + float2(q, q)));
    hits += bodyHit(ro, primaryDir(coord + float2(-q, q)));
    hits += bodyHit(ro, primaryDir(coord + float2(q, -q)));
    hits += bodyHit(ro, primaryDir(coord + float2(-q, -q)));
    float cov = hits * 0.25;
    if (cov <= 0.0) { return safeContent(coord); }             // 覆盖率 0 ⇒ 背景（不做追踪）

    // ---- ⑤ 完整追踪（coverage > 0 才做）
    float iorK = max(uIor, 1.0001);                            // 分母保护（ior > 1）
    float fresK = clamp(uFresnel, 0.0, 1.0);
    float disp = clamp(uDispersion, 0.0, 0.5);
    float3 col = float3(0.0);
    if (uDebug > 2.5) {                                        // debug=3：仅折射（无色散 / Fresnel）
        col = traceColor(ro, rd, iorK, coord, 0.0);
    } else if (disp > 0.001) {                                 // 色散：RGB 三条射线各做完整追踪
        float3 cR = traceColor(ro, rd, iorK * (1.0 + disp), coord, fresK);
        float3 cG = traceColor(ro, rd, iorK, coord, fresK);
        float3 cB = traceColor(ro, rd, iorK * (1.0 - disp), coord, fresK);
        col = float3(cR.x, cG.y, cB.z);                        // 各取自家通道合成
    } else {
        col = traceColor(ro, rd, iorK, coord, fresK);
    }
    if (uDebug > 0.5 && uDebug < 2.5) {                        // debug=1 / 2（debug=3 保留上面的纯折射结果）
        float4 en = enterHit(ro, rd);
        if (en.x < 0.0) {
            col = float3(safeContent(coord).rgb);              // 中心射线未命中实体（盒内但在轮廓外）⇒ 背景
        } else if (uDebug < 1.5) {
            col = en.yzw * 0.5 + float3(0.5, 0.5, 0.5);        // debug=1：面法线着色
        } else {
            float fid = facetIdOf(en.yzw);                     // debug=2：面 ID 伪彩
            col = float3(fract(0.6180339 * fid + 0.13), fract(0.3090170 * fid + 0.51), fract(0.7548777 * fid + 0.27));
        }
    }

    // ---- ⑥ 覆盖率接进【输出颜色】（边缘 AA：4 样本覆盖率做一次平滑过渡，不用于早退）
    half3 bg = safeContent(coord).rgb;
    float covS = cov * cov * (3.0 - 2.0 * cov);
    float3 outc = mix(float3(bg), col, covS);
    return half4(half3(outc), half(1.0));
}

"""
}
