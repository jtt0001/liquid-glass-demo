package com.example.liquidglass.diamond

import java.util.concurrent.ConcurrentHashMap

/**
 * 【3D 钻石演示】切工枚举。
 *
 * @param displayName 界面显示名（一级菜单 / 演示页标题）。
 * @param facetCount 面数（与 [DiamondModel.facets] 返回的 (nx,ny,nz,d) 组数一致）。
 */
enum class DiamondCut(val displayName: String, val facetCount: Int) {

    /** 简化明亮式切工：1 台面 + 8 冠部面 + 8 亭部面 = 17 面（冻结几何）。 */
    BRILLIANT17("标准明亮式 · 17 面", 17);

    companion object {
        /**
         * 按名字查切工（调试桥 `setParams(cutName=...)` 用）。
         * 接受枚举名（大小写不敏感）或显示名；未知返回 null。
         */
        fun byName(n: String): DiamondCut? {
            val q = n.trim()
            if (q.isEmpty()) { return null }
            return entries.firstOrNull { it.name.equals(q, ignoreCase = true) || it.displayName == q }
        }
    }
}

/**
 * 【3D 钻石演示】冻结几何（简化明亮式切工 17 面）。
 *
 * 坐标系：模型半径 = 1，+z = 台面方向；八边形顶点角 = 22.5° + 45°·k（k = 0..7）：
 *  · 腰棱环 r = 1、z = 0（本切工腰厚 = 0 ⇒ 腰棱是锐边）
 *  · 台面八边形外接半径 0.58、位于 z = +0.288（冠高）
 *  · 尖底 (culet) = (0, 0, -0.86)（亭深）
 *
 * 17 面（法线朝外、单位长；内部 = dot(n, p) <= d；按索引顺序）：
 *  · 索引 0        台面：n = (0,0,1)，d = 0.288
 *  · 索引 1..8     冠部 k = 0..7：过腰棱边 [v_k, v_(k+1)]（z=0）与台面边 [t_k, t_(k+1)]（z=0.288）的平面
 *  · 索引 9..16    亭部 k = 0..7：过腰棱边 [v_k, v_(k+1)]（z=0）与尖底点的平面
 *
 * 相机：(0, 0, [CAM_Z])，z = 0 平面恒等映射到"半径单位"屏幕坐标（焦距 = [CAM_Z]）；
 * 背景平面 z = -[DEPTH]。
 *
 * 本文件是几何的**唯一 Kotlin 侧真相**：[DiamondAgsl] 的 AGSL 源码里那张字面量表
 * 必须与 [facets] 一致（由 tools/verify_diamond_geometry.py 离线逐面断言，容差 1e-5）。
 */
object DiamondModel {

    // ---- 冻结常量（名字 + 数值被 tools/verify_diamond_geometry.py 解析并断言）----

    /** 腰棱环半径（模型半径单位；冻结 = 1.0）。 */
    const val WAIST_R: Float = 1.0f

    /** 台面八边形外接半径（冻结 = 0.58）。 */
    const val TABLE_R: Float = 0.58f

    /** 冠高：台面所在 z（冻结 = 0.288）。 */
    const val CROWN_Z: Float = 0.288f

    /** 亭深：尖底所在 z（冻结 = -0.86）。 */
    const val CULET_Z: Float = -0.86f

    /** 顶点角基值（度；冻结 = 22.5）。 */
    const val VERTEX_ANGLE_BASE_DEG: Float = 22.5f

    /** 顶点角步长（度；冻结 = 45.0）。 */
    const val VERTEX_ANGLE_STEP_DEG: Float = 45.0f

    /** 面数（冻结 = 17）。 */
    const val FACET_COUNT: Int = 17

    /** 相机距离（半径倍数；冻结 = 3.6）。 */
    const val CAM_Z: Float = 3.6f

    /** 背景平面深度（半径倍数；冻结 = 6.0）。 */
    const val DEPTH: Float = 6.0f

    /** 顶点数 = 8 腰棱 + 8 台面 + 1 尖底（冻结 = 17）。 */
    const val VERTEX_COUNT: Int = 17

    // ---- 缓存（面表 / 顶点表按切工各算一次；对外返回防御性副本）----

    private val facetCache = ConcurrentHashMap<DiamondCut, FloatArray>()
    private val vertexCache = ConcurrentHashMap<DiamondCut, FloatArray>()

    /**
     * 面表：`(nx, ny, nz, d) × N`，长度 = `4 * cut.facetCount`（BRILLIANT17 ⇒ 68）。
     *
     * 语义与 AGSL 侧一致：法线单位长、朝外；实体内部 = `dot(n, p) <= d`。
     * 返回的是副本，调用方可以随意改。
     */
    fun facets(cut: DiamondCut): FloatArray {
        val cached = facetCache[cut]
        if (cached != null) { return cached.copyOf() }
        val built = buildFacets(cut)
        facetCache[cut] = built
        return built.copyOf()
    }

    /**
     * 顶点表：`(x, y, z) × N`，长度 = `3 * VERTEX_COUNT`（= 51）。
     *
     * 顺序：`v_0..v_7`（腰棱环，r=1、z=0）→ `t_0..t_7`（台面八边形，r=0.58、z=0.288）→ 尖底 `(0,0,-0.86)`。
     * 全部 17 个顶点都满足所有 17 个半空间（凸性，由离线脚本断言）。
     */
    fun vertices(cut: DiamondCut): FloatArray {
        val cached = vertexCache[cut]
        if (cached != null) { return cached.copyOf() }
        val built = buildVertices(cut)
        vertexCache[cut] = built
        return built.copyOf()
    }

    // ---- 公式（唯一真相；下面的实现必须与 AGSL 字面量表逐面一致）----

    private fun buildFacets(cut: DiamondCut): FloatArray = when (cut) {
        DiamondCut.BRILLIANT17 -> {
            val out = FloatArray(FACET_COUNT * 4)
            // 索引 0：台面
            putPlane(out, 0, 0.0, 0.0, 1.0, CROWN_Z.toDouble())
            // 索引 1..8：冠部 —— 过腰棱边 [v_k, v_(k+1)]（z=0）与台面边 [t_k, t_(k+1)]（z=0.288）
            for (k in 0 until 8) {
                val k2 = (k + 1) % 8
                val v0 = waistVertex(k)
                val v1 = waistVertex(k2)
                val t0 = tableVertex(k)
                val n = unit(cross(sub(v1, v0), sub(t0, v0)))   // 朝外：z 分量取正
                val nn = if (n[2] < 0.0) doubleArrayOf(-n[0], -n[1], -n[2]) else n
                putPlane(out, 1 + k, nn[0], nn[1], nn[2], dot(nn, v0))
            }
            // 索引 9..16：亭部 —— 过腰棱边 [v_k, v_(k+1)]（z=0）与尖底 (0,0,-0.86)
            for (k in 0 until 8) {
                val k2 = (k + 1) % 8
                val v0 = waistVertex(k)
                val v1 = waistVertex(k2)
                val culet = doubleArrayOf(0.0, 0.0, CULET_Z.toDouble())
                val n = unit(cross(sub(v1, v0), sub(culet, v0)))  // 朝外：z 分量取负
                val nn = if (n[2] > 0.0) doubleArrayOf(-n[0], -n[1], -n[2]) else n
                putPlane(out, 9 + k, nn[0], nn[1], nn[2], dot(nn, v0))
            }
            out
        }
    }

    private fun buildVertices(cut: DiamondCut): FloatArray = when (cut) {
        DiamondCut.BRILLIANT17 -> {
            val out = FloatArray(VERTEX_COUNT * 3)
            for (k in 0 until 8) {
                val v = waistVertex(k)
                out[k * 3] = v[0].toFloat()
                out[k * 3 + 1] = v[1].toFloat()
                out[k * 3 + 2] = v[2].toFloat()
            }
            for (k in 0 until 8) {
                val t = tableVertex(k)
                val o = (8 + k) * 3
                out[o] = t[0].toFloat()
                out[o + 1] = t[1].toFloat()
                out[o + 2] = t[2].toFloat()
            }
            val o = 16 * 3
            out[o] = 0f
            out[o + 1] = 0f
            out[o + 2] = CULET_Z
            out
        }
    }

    /** 腰棱环顶点 v_k = (cos θ, sin θ, 0)，θ = 22.5° + 45°·k。 */
    private fun waistVertex(k: Int): DoubleArray {
        val a = Math.toRadians((VERTEX_ANGLE_BASE_DEG + VERTEX_ANGLE_STEP_DEG * k).toDouble())
        return doubleArrayOf(WAIST_R.toDouble() * Math.cos(a), WAIST_R.toDouble() * Math.sin(a), 0.0)
    }

    /** 台面八边形顶点 t_k = 0.58 · (cos θ, sin θ)，z = 0.288。 */
    private fun tableVertex(k: Int): DoubleArray {
        val a = Math.toRadians((VERTEX_ANGLE_BASE_DEG + VERTEX_ANGLE_STEP_DEG * k).toDouble())
        return doubleArrayOf(TABLE_R.toDouble() * Math.cos(a), TABLE_R.toDouble() * Math.sin(a), CROWN_Z.toDouble())
    }

    private fun putPlane(out: FloatArray, idx: Int, nx: Double, ny: Double, nz: Double, d: Double) {
        out[idx * 4] = nx.toFloat()
        out[idx * 4 + 1] = ny.toFloat()
        out[idx * 4 + 2] = nz.toFloat()
        out[idx * 4 + 3] = d.toFloat()
    }

    private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun unit(v: DoubleArray): DoubleArray {
        val l = Math.sqrt(dot(v, v))
        return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }
}
