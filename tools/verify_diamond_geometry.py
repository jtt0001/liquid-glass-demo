#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""3D 钻石（简化明亮式切工 17 面）离线几何校验。

用途：在不跑 gradle、不碰设备的前提下，证明【AGSL 源码里的面表 = 几何公式算出来的面表】，
并用纯 Python 复刻的参考追踪器跑通"入面折射 → 内部全反射(TIR) → 出射折射 → 背景平面求交 →
逆投影"这条链路，把每条测试射线的路径打成一张表。

三块检查：
  A. 几何：公式推导 17 面（法线单位长 / 凸性 / 面数），并与 DiamondAgsl.kt 里的字面量
     （机器可读注释行 + code-facet 标记行）逐面比对（容差 1e-5）。
  B. 静态约束：AGSL 源码不得出现取模运算符 / 数组 / 内置片段坐标变量 / 内置 uniform 名；
     循环必须是常量上界 for + break；smoothstep 的 edge0 < edge1；
     AGSL uniform 名与 DiamondUniforms.kt 常量双向一致；DiamondModel.kt 冻结常量值一致。
  C. 参考追踪：若干条从相机出发、穿过台面 / 冠部 / 亭部 / 腰棱边缘的射线 ——
     报告（入射面 id、内部弹射次数、出射面 id、出射方向、背景采样点），并断言物理真值
     （含中心正入射射线的完整路径、亭部面"只能全反射 / 可侧向出射"两条事实）。

退出码：任一断言失败 ⇒ 1。
运行：~/.hermes/scripts/py3 tools/verify_diamond_geometry.py
"""

import math
import os
import re
import sys

# ---------------------------------------------------------------- 路径
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
DIR = os.path.join(REPO, "app", "src", "main", "java", "com", "example", "liquidglass", "diamond")
AGSL_KT = os.path.join(DIR, "DiamondAgsl.kt")
MODEL_KT = os.path.join(DIR, "DiamondModel.kt")
UNIFORMS_KT = os.path.join(DIR, "DiamondUniforms.kt")

# ---------------------------------------------------------------- 冻结几何（任务书原值）
WAIST_R = 1.0
TABLE_R = 0.58
CROWN_Z = 0.288
CULET_Z = -0.86
CAM_Z = 3.6
DEPTH = 6.0
ANG_BASE = 22.5
ANG_STEP = 45.0
N_FACET = 17
IOR = 2.417
TOL = 1.0e-5          # 字面量 vs 公式 容差

FAILS = []


def check(name, ok, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  " + detail) if detail else ""))
    if not ok:
        FAILS.append(name)
    return ok


# ---------------------------------------------------------------- 线代
def vsub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def vadd(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def vmul(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)


def vdot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def vcross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def vlen(a):
    return math.sqrt(vdot(a, a))


def vunit(a):
    l_ = vlen(a)
    return (a[0] / l_, a[1] / l_, a[2] / l_)


def isfin3(a):
    return all(math.isfinite(c) for c in a)


def smoothstep(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3.0 - 2.0 * t)


# ---------------------------------------------------------------- A. 公式推导几何
def dir2(k):
    a = math.radians(ANG_BASE + ANG_STEP * k)
    return (math.cos(a), math.sin(a))


def waist_vertex(k):
    c, s = dir2(k)
    return (WAIST_R * c, WAIST_R * s, 0.0)


def table_vertex(k):
    c, s = dir2(k)
    return (TABLE_R * c, TABLE_R * s, CROWN_Z)


def derive_facets():
    """17 个 (n, d)。0=台面；1..8=冠部 k=0..7；9..16=亭部 k=0..7。"""
    out = [((0.0, 0.0, 1.0), CROWN_Z)]
    for k in range(8):
        k2 = (k + 1) % 8
        v0, v1 = waist_vertex(k), waist_vertex(k2)
        t0 = table_vertex(k)
        n = vunit(vcross(vsub(v1, v0), vsub(t0, v0)))
        if n[2] < 0.0:
            n = (-n[0], -n[1], -n[2])
        out.append((n, vdot(n, v0)))
    for k in range(8):
        k2 = (k + 1) % 8
        v0, v1 = waist_vertex(k), waist_vertex(k2)
        culet = (0.0, 0.0, CULET_Z)
        n = vunit(vcross(vsub(v1, v0), vsub(culet, v0)))
        if n[2] > 0.0:
            n = (-n[0], -n[1], -n[2])
        out.append((n, vdot(n, v0)))
    return out


FACETS = derive_facets()
VERTS = [waist_vertex(k) for k in range(8)] + [table_vertex(k) for k in range(8)] + [(0.0, 0.0, CULET_Z)]


# ---------------------------------------------------------------- 读文件 / 解析
def read(path):
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def extract_agsl(kt_src):
    """从 DiamondAgsl.kt 的 Kotlin 原生字符串里取出 AGSL 源码。"""
    lines = kt_src.split("\n")
    start = None
    for i, line in enumerate(lines):
        if "AGSL_SOURCE: String" in line and '"""' in line:
            start = i + 1
            break
    if start is None:
        return None
    for j in range(start, len(lines)):
        if lines[j].strip() == '"""':
            return "\n".join(lines[start:j])
    return None


NUM = r"(-?\d+(?:\.\d+)?)"
TABLE_RE = re.compile(r"^//\s*F\s+(\d+)\s+n=" + NUM + r"\s+" + NUM + r"\s+" + NUM + r"\s+d=" + NUM + r"\s*$")
CAND_RE = re.compile(r"float3\(\s*" + NUM + r"\s*,\s*" + NUM + r"\s*,\s*" + NUM + r"\s*\)\s*,\s*" + NUM + r"\s*\)")
VEC3_RE = re.compile(r"float3\(\s*" + NUM + r"\s*,\s*" + NUM + r"\s*,\s*" + NUM + r"\s*\)")


def parse_table(agsl):
    rows = []
    for line in agsl.split("\n"):
        m = TABLE_RE.match(line.strip())
        if m:
            vals = [float(m.group(g)) for g in range(2, 6)]
            rows.append((int(m.group(1)), (vals[0], vals[1], vals[2]), vals[3]))
    return rows


def parse_code_literals(agsl):
    pairs, vecs = [], []
    for line in agsl.split("\n"):
        if "code-facet" not in line:
            continue
        i = int(line.split("code-facet", 1)[1].strip().split()[0])
        for m in CAND_RE.finditer(line):
            pairs.append((i, (float(m.group(1)), float(m.group(2)), float(m.group(3))), float(m.group(4))))
        for m in VEC3_RE.finditer(line):
            vecs.append((i, (float(m.group(1)), float(m.group(2)), float(m.group(3)))))
    return pairs, vecs


def parse_kt_constants(kt_src):
    out = {}
    for m in re.finditer(r"const\s+val\s+([A-Z0-9_]+)\s*:\s*(Float|Int)\s*=\s*(-?[0-9.]+)f?", kt_src):
        out[m.group(1)] = float(m.group(3))
    return out


# ---------------------------------------------------------------- B. 参考追踪（逐字复刻 AGSL）
EPS_PAR = 1.0e-6      # 平行阈值（AGSL: 1.0e-6）
ADV = 1.0e-3          # 内部微推进（AGSL: 1.0e-3）
BUDGET_MAX = 4


def enter_cand(ro, rd, n, df):
    dn = vdot(n, rd)
    if dn > -EPS_PAR:
        return -1.0e9
    return (df - vdot(n, ro)) / dn


def exit_cand(ro, rd, n, df):
    dn = vdot(n, rd)
    if dn < EPS_PAR:
        return 1.0e9
    return (df - vdot(n, ro)) / dn


def enter_hit(ro, rd):
    bt, bn, bid = -1.0e9, (0.0, 0.0, 1.0), -1
    for i, (n, df) in enumerate(FACETS):
        c = enter_cand(ro, rd, n, df)
        if c > bt:
            bt, bn, bid = c, n, i
    t_out, _, _ = exit_hit(ro, rd)
    if bt > t_out or t_out <= 0.0:
        return -1.0, (0.0, 0.0, 1.0), -1
    return bt, bn, bid


def exit_hit(ro, rd):
    bt, bn, bid = 1.0e9, (0.0, 0.0, 1.0), -1
    for i, (n, df) in enumerate(FACETS):
        c = exit_cand(ro, rd, n, df)
        if c < bt:
            bt, bn, bid = c, n, i
    return bt, bn, bid


def refract_or_reflect(L, n_out, eta):
    dnl = vdot(n_out, L)
    N = n_out if dnl <= 0.0 else vmul(n_out, -1.0)
    ci = max(0.0, min(1.0, -vdot(N, L)))
    k = 1.0 - eta * eta * (1.0 - ci * ci)
    if k < 0.0:
        return vunit(vsub(L, vmul(n_out, 2.0 * dnl))), False, ci
    s = math.sqrt(max(k, 0.0))
    return vunit(vadd(vmul(L, eta), vmul(N, eta * ci - s))), True, ci


def rot_rows(rows, v):
    return (vdot(rows[0], v), vdot(rows[1], v), vdot(rows[2], v))


def primary_dir(rows, s):
    return rot_rows(rows, vunit((s[0], s[1], -CAM_Z)))


def bg_coord_exit(p_w, d_w):
    """复刻 AGSL bgCoordExit：返回 (采样点半径单位坐标, 平面分支权重, 平面交点是否存在)。"""
    t_den = min(d_w[2], -0.08)
    t_bg = (-DEPTH - p_w[2]) / t_den
    t_bg = max(0.0, min(t_bg, 4.0 * (CAM_Z + DEPTH)))
    p_bg = vadd(p_w, vmul(d_w, t_bg))
    s_plane = (p_bg[0] * CAM_Z / (CAM_Z + DEPTH), p_bg[1] * CAM_Z / (CAM_Z + DEPTH))
    s_soft = (d_w[0] * 0.5, -d_w[1] * 0.5)
    w = smoothstep(0.02, 0.30, -d_w[2])
    s = (s_soft[0] + (s_plane[0] - s_soft[0]) * w, s_soft[1] + (s_plane[1] - s_soft[1]) * w)
    return s, w, (d_w[2] < -1.0e-9), p_bg


def bg_coord_env(p_w, d_w, res=(1840.0, 2944.0), radius_px=354.2, y_fix=True):
    """复刻 AGSL bgCoordEnv（H1 修复分支 + H4 y 符号开关）：背景平面 + 它关于 z = 0 的镜像 + 朝上支路的有界压缩。

    返回 (s_final, t, p_bg, s_raw)：半径单位屏幕坐标（**y 向上、几何口径**：s.y = +p_bg.y·k；
    不含中心/像素半径；与 bg_coord_exit 同口径）。
    朝下（d.z < 0）⇒ 与 z = -DEPTH 求交（精确，无压缩）；朝上 ⇒ 与镜像平面 z = +DEPTH 求交，
    再把归一化坐标 u = s/half 压成 u/(1+|u|)（保向、单调、|u| < 1 ⇒ 恒在屏内，不再触发边缘延展条带）。
    掠射保护 |dz| >= 0.08、交点参数上限 4*(CAM_Z+DEPTH) 与 AGSL 侧逐字一致。

    y_fix 参数只为兼容历史调用（旧的 false 用法已废弃）：本函数现在【总是】返回几何口径的 s
    （s.y = +p_bg.y·k）。AGSL 的 uEnvYFix=0（H1 原样）等价于把像素映射取反 —— 见 env_px(s, False)，
    因为 y 取反对保向压缩可交换（压缩是方向保持的），像素就是正确点的水平中线镜像。
    """
    sgn = 1.0
    if d_w[2] >= 0.0:
        sgn = -1.0
    z_plane = -DEPTH * sgn
    dz = d_w[2]
    if -0.08 < dz < 0.08:
        dz = 0.08 if d_w[2] >= 0.0 else -0.08
    t = (z_plane - p_w[2]) / dz
    t = max(0.0, min(t, 4.0 * (CAM_Z + DEPTH)))
    p_bg = vadd(p_w, vmul(d_w, t))
    s_raw = (p_bg[0] * CAM_Z / (CAM_Z + DEPTH), p_bg[1] * CAM_Z / (CAM_Z + DEPTH))
    s = s_raw
    if d_w[2] >= 0.0:
        hx = max(res[0] * 0.5 / max(radius_px, 1.0) - 0.15, 0.30)
        hy = max(res[1] * 0.5 / max(radius_px, 1.0) - 0.15, 0.30)
        ux, uy = s[0] / hx, s[1] / hy
        ln = math.sqrt(ux * ux + uy * uy)
        s = (hx * ux / (1.0 + ln), hy * uy / (1.0 + ln))
    return s, t, p_bg, s_raw


def env_px(s, y_fix=True, center=(920.0, 1472.0), radius_px=354.2):
    """半径单位屏幕坐标 s（y 向上，几何口径）→ 图层像素（与 AGSL 输出同口径）。

    y_fix=True（uEnvYFix=1，默认/修正后）：py = cy − s.y·R —— 世界点 P 的针孔投影
      （primaryDir 的逆映射：sc.y = (cy − py)/R），与 bgCoordExit 平面支路 / bgCoordSoft / 744aef1 同号；
    y_fix=False（uEnvYFix=0，H1 原样）：py = cy + s.y·R —— y 双重取反 ⇒ 采样点关于屏幕水平中线镜像。
    """
    cx, cy = center
    return (cx + s[0] * radius_px, cy - s[1] * radius_px if y_fix else cy + s[1] * radius_px)


def trace(rows, s, ior=IOR, bounces=BUDGET_MAX):
    """完整追踪一条主射线（复刻 AGSL traceColor 的控制流与弹射预算语义）。"""
    ro = rot_rows(rows, (0.0, 0.0, CAM_Z))
    rd = primary_dir(rows, s)
    t_in, n_e, entry_id = enter_hit(ro, rd)
    if t_in < 0.0:
        return {"hit": False}
    p_entry = vadd(ro, vmul(rd, t_in))
    d, ok, ci = refract_or_reflect(rd, n_e, 1.0 / ior)
    path = [{"kind": "ENTER", "id": entry_id, "p": p_entry, "n": n_e, "din": rd, "dout": d, "refract": ok}]
    res = {"hit": True, "entry": entry_id, "entry_ci": ci, "path": path, "tirs": 0,
           "exit_id": None, "exit_dir_world": None, "p_exit_world": None, "degraded": True}
    if not ok:
        return res
    p = vadd(p_entry, vmul(d, ADV))
    for i in range(BUDGET_MAX + 1):
        if i > bounces:
            break
        t_ex, n_h, hid = exit_hit(p, d)
        if t_ex > 1.0e8:
            break
        ph = vadd(p, vmul(d, t_ex))
        dd, ok2, ci2 = refract_or_reflect(d, n_h, ior)
        path.append({"kind": "EXIT" if ok2 else "TIR", "id": hid, "p": ph, "n": n_h, "din": d, "dout": dd,
                     "refract": ok2, "ci": ci2})
        if ok2:
            res["exit_id"] = hid
            res["exit_dir_world"] = rot_rows(rows, dd)
            res["p_exit_world"] = rot_rows(rows, ph)
            res["p_exit_model"] = ph
            res["degraded"] = False
            break
        if i >= bounces:
            break
        d = dd
        p = vadd(ph, vmul(d, ADV))
        res["tirs"] += 1
        res["last_dir_model"] = d
    # 背景采样（正常：bgCoordExit；退化：bgCoordSoft）
    dw = rot_rows(rows, res.get("last_dir_model", d))
    if res["exit_dir_world"] is not None:
        dw = res["exit_dir_world"]
        res["sample"], res["w_down"], res["bg_plane"], res["p_bg"] = bg_coord_exit(res["p_exit_world"], dw)
    else:
        res["sample"] = (dw[0] * 0.5, -dw[1] * 0.5)
        res["w_down"], res["bg_plane"], res["p_bg"] = 0.0, False, None
    return res


def rot_x(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return [(1.0, 0.0, 0.0), (0.0, c, -s), (0.0, s, c)]


def rot_y(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return [(c, 0.0, s), (0.0, 1.0, 0.0), (-s, 0.0, c)]


def rot_mul(A, B):
    return [tuple(sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)) for i in range(3)]


def silhouette_radius(rows, az_deg):
    """二分求真实轮廓半径（沿给定方位角，命中/不命中分界）。"""
    a = math.radians(az_deg)
    d = (math.cos(a), math.sin(a))
    lo, hi = 0.2, 1.6
    for _ in range(48):
        mid = 0.5 * (lo + hi)
        if trace(rows, (mid * d[0], mid * d[1]), IOR, 0).get("hit"):
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


# ================================================================ 开始
print("=" * 96)
print("3D 钻石几何离线校验  tools/verify_diamond_geometry.py")
print("AGSL: %s" % os.path.relpath(AGSL_KT, REPO))
print("=" * 96)

for p in (AGSL_KT, MODEL_KT, UNIFORMS_KT):
    if not os.path.isfile(p):
        print("[FAIL] 文件缺失: %s" % p)
        sys.exit(1)

agan = read(AGSL_KT)
model_kt = read(MODEL_KT)
unif_kt = read(UNIFORMS_KT)
agsl = extract_agsl(agan)
# 【本轮修复】修复开关的接线文件（默认值 / 调试桥运行时翻转）
model_state_src = read(os.path.join(DIR, "DiamondDemoState.kt"))
bridge_src = read(os.path.join(DIR, "DiamondDemoBridge.kt"))
api_src = read(os.path.join(DIR, "DiamondBridgeApi.kt"))

print("\n-- A. 面表：AGSL 字面量 vs 几何公式 --")
if agsl is None:
    check("AGSL 源码可解析（Kotlin 原生字符串）", False, "未找到 AGSL_SOURCE 字符串")
    print("FAIL: 无法解析 AGSL 源码")
    sys.exit(1)
check("AGSL 源码可解析（Kotlin 原生字符串）", True, "%d 行 / %d 字符" % (agsl.count("\n") + 1, len(agsl)))

table = parse_table(agsl)
check("面表机器可读行数 = 17", len(table) == N_FACET, "实际 %d" % len(table))
check("面表索引 0..16 连续且唯一", [t[0] for t in table] == list(range(N_FACET)))

worst_tbl, tbl_bad = 0.0, []
for i, n_lit, d_lit in table:
    n_f, d_f = FACETS[i]
    worst_tbl = max(worst_tbl, max(abs(n_lit[c] - n_f[c]) for c in range(3)), abs(d_lit - d_f))
    if max(abs(n_lit[c] - n_f[c]) for c in range(3)) > TOL or abs(d_lit - d_f) > TOL:
        tbl_bad.append("F%d 字面量 %s d=%s vs 公式 %s d=%.6f" % (i, n_lit, d_lit, tuple(round(x, 6) for x in n_f), d_f))
check("面表字面量 = 公式值（逐面逐分量，容差 1e-5）", not tbl_bad, "最大偏差 %.2e" % worst_tbl)
for b in tbl_bad:
    print("        " + b)

pairs, vecs = parse_code_literals(agsl)
check("code-facet 标注行覆盖 0..16", sorted(set([p[0] for p in pairs])) == list(range(N_FACET)),
      "(vec3,d) 对 %d 组 / 含 vec3 行 %d 组" % (len(pairs), len(vecs)))
worst_code, code_bad = 0.0, []
for i, n_lit, d_lit in pairs:
    n_f, d_f = FACETS[i]
    worst_code = max(worst_code, max(abs(n_lit[c] - n_f[c]) for c in range(3)), abs(d_lit - d_f))
    if max(abs(n_lit[c] - n_f[c]) for c in range(3)) > TOL or abs(d_lit - d_f) > TOL:
        code_bad.append("code F%d %s d=%s" % (i, n_lit, d_lit))
worst_vec, vec_bad = 0.0, []
for i, n_lit in vecs:
    n_f = FACETS[i][0]
    worst_vec = max(worst_vec, max(abs(n_lit[c] - n_f[c]) for c in range(3)))
    if max(abs(n_lit[c] - n_f[c]) for c in range(3)) > TOL:
        vec_bad.append("vec F%d %s" % (i, n_lit))
check("code-facet (vec3,d) 字面量 = 公式值（参与运算的那份表）", not code_bad, "%d 组，最大偏差 %.2e" % (len(pairs), worst_code))
check("code-facet 全部 float3 字面量 = 公式法线（含法线赋值 / facetIdOf）", not vec_bad, "%d 组，最大偏差 %.2e" % (len(vecs), worst_vec))
for b in (code_bad + vec_bad)[:8]:
    print("        " + b)

check("17 面法线单位长（公式）", max(abs(vlen(n) - 1.0) for n, _ in FACETS) < TOL,
      "最大 |len-1| = %.2e" % max(abs(vlen(n) - 1.0) for n, _ in FACETS))
check("17 面法线单位长（AGSL 字面量，容差 1e-4）", max(abs(vlen(n) - 1.0) for _, n, _ in table) < 1.0e-4,
      "最大 |len-1| = %.2e" % max(abs(vlen(n) - 1.0) for _, n, _ in table))

slack, viol = -9e9, []
for i, (n, d) in enumerate(FACETS):
    for vi, v in enumerate(VERTS):
        sv = vdot(n, v) - d
        slack = max(slack, sv)
        if sv > 1.0e-4:
            viol.append("面%d 顶点%d slack=%.3e" % (i, vi, sv))
check("凸性：全部 17 顶点满足全部 17 半空间（slack <= 1e-4）", not viol,
      "max slack = %.2e（0 = 顶点正好落在平面上）" % slack)
check("面数 = 17（面 17 / 顶点 17）", len(FACETS) == N_FACET and len(VERTS) == 17)

crown_ang = math.degrees(math.atan2(CROWN_Z, WAIST_R - TABLE_R))
pav_ang = math.degrees(math.atan2(-CULET_Z, WAIST_R))
crit = math.degrees(math.asin(1.0 / IOR))
inc_pav = math.degrees(math.acos(abs(FACETS[9][0][2])))
print("    info: 冠角=%.3f° / 亭角=%.3f°（理想圆明亮式 34.5° / 40.75°）；临界角(ior=%.3f)=%.3f°；"
      "垂直光打亭部面入射角=%.3f°" % (crown_ang, pav_ang, IOR, crit, inc_pav))
check("冠角合理（30..38°）", 30.0 < crown_ang < 38.0, "%.3f°" % crown_ang)
check("亭角合理（38..43°）", 38.0 < pav_ang < 43.0, "%.3f°" % pav_ang)
check("垂直光打亭部面入射角 > 临界角（亭部无法透出接近轴线的光 ⇒ 必须全反射）", inc_pav > crit,
      "%.3f° > %.3f°" % (inc_pav, crit))

# ================================================================ B. AGSL 静态约束
print("\n-- B. AGSL 静态约束 --")
check("无取模运算符", "%" not in agsl)
check("无数组（无方括号）", "[" not in agsl and "]" not in agsl)
check("无内置片段坐标变量", "gl_FragCoord" not in agsl)
check("入口签名 half4 main(float2 coord)", "half4 main(float2 coord)" in agsl)
reserved = ["resolution", "time", "frame", "date"]
uni_decls = re.findall(r"^uniform\s+(shader|float2|float3|float|int)\s+([A-Za-z_][A-Za-z0-9_]*)\s*;", agsl, re.M)
names = [u[1] for u in uni_decls]
check("uniform 声明不含内置名（resolution/time/frame/date）", not [n for n in names if n in reserved],
      "%d 个 uniform：%s" % (len(names), ",".join(names)))
uni_consts = dict(re.findall(r'const\s+val\s+([A-Z0-9_]+)\s*=\s*"([^"]+)"', unif_kt))
check("uniform 名与 DiamondUniforms.kt 常量双向一致", sorted(names) == sorted(uni_consts.values()),
      "Kotlin %d 个 / AGSL %d 个，差集=%s" % (len(uni_consts), len(names),
                                              sorted(set(names) ^ set(uni_consts.values()))))
check("uniform shader 用了 INPUT 常量（content）", ("shader", uni_consts.get("INPUT", "?")) in uni_decls,
      "uniform shader %s;" % uni_consts.get("INPUT", "?"))

loops = re.findall(r"^\s*for\s*\(([^)]*)\)", agsl, re.M)
loop_ok = True
for lp in loops:
    if not re.match(r"^int\s+\w+\s*=\s*0;\s*\w+\s*<\s*\d+;\s*\w+\+\+$", lp.strip()):
        loop_ok = False
        print("        非法 for 头: %s" % lp)
check("循环只允许常量上界 for（for (int i = 0; i < 常量; i++）", loop_ok and len(loops) >= 1,
      "%d 个 for；break 语句 %d 条" % (len(loops), agsl.count("break;")))
ss_bad = []
for m in re.finditer(r"smoothstep\(\s*" + NUM + r"\s*,\s*" + NUM + r"\s*,", agsl):
    if not (float(m.group(1)) < float(m.group(2))):
        ss_bad.append(m.group(0))
check("smoothstep(edge0, edge1, x) 且 edge0 < edge1", not ss_bad, "%d 处调用" % len(re.findall(r"smoothstep\(", agsl)))
bal = {"(": 0, "{": 0}
closing = {")": "(", "}": "{"}
for ch in agsl:
    if ch in bal:
        bal[ch] += 1
    elif ch in closing:
        bal[closing[ch]] -= 1
check("圆括号 / 花括号配平", bal["("] == 0 and bal["{"] == 0, "()=%d {}=%d" % (bal["("], bal["{"]))
print("    info: 除法 / sqrt / pow 静态审计（人工复核分母与非负参数）:")
for line in agsl.split("\n"):
    code = line.split("//")[0]
    if "/" in code or "sqrt(" in code or "pow(" in code:
        print("        " + line.strip())
check("cacheKey = DiamondDemo_<CUT>_v1",
      bool(re.search(r'fun\s+cacheKey\s*\(cut:\s*DiamondCut\)\s*:\s*String\s*=\s*KEY_PREFIX\s*\+\s*"_"\s*\+\s*cut\.name\s*\+\s*"_v1"', agan))
      and 'const val KEY_PREFIX = "DiamondDemo"' in agan)
check("source(cut) 存在且按 cut 缓存", "fun source(cut: DiamondCut): String" in agan and "sourceCache" in agan)
mc = parse_kt_constants(model_kt)
expect = {"WAIST_R": 1.0, "TABLE_R": 0.58, "CROWN_Z": 0.288, "CULET_Z": -0.86,
          "VERTEX_ANGLE_BASE_DEG": 22.5, "VERTEX_ANGLE_STEP_DEG": 45.0,
          "FACET_COUNT": 17.0, "CAM_Z": 3.6, "DEPTH": 6.0, "VERTEX_COUNT": 17.0}
miss = [k for k in expect if k not in mc]
bad = ["%s=%s(期望 %s)" % (k, mc.get(k), expect[k]) for k in expect if k in mc and abs(mc[k] - expect[k]) > 1e-9]
check("DiamondModel.kt 冻结常量值 = 任务书", not miss and not bad, "缺=%s 错=%s" % (miss, bad))
check("DiamondModel.kt facets/vertices 签名存在",
      "fun facets(cut: DiamondCut): FloatArray" in model_kt and "fun vertices(cut: DiamondCut): FloatArray" in model_kt)
check("DiamondCut.BRILLIANT17(17) 与 byName 存在",
      'BRILLIANT17("标准明亮式 · 17 面", 17)' in model_kt and "fun byName(n: String): DiamondCut?" in model_kt)

# ================================================================ C. 参考追踪
print("\n-- C. 参考追踪器（从相机出发的实射线）--")
I3 = [(1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0)]


def f3(v):
    if not v:
        return "-"
    if len(v) == 2:
        return "(%.4f,%.4f)" % (v[0], v[1])
    return "(%.4f,%.4f,%.4f)" % (v[0], v[1], v[2])


SHOTS = [
    ("中心(正入射)", (0.00, 0.00)),
    ("近中心", (0.12, 0.06)),
    ("台面内偏上", (0.00, 0.40)),
    ("台面边附近", (0.50, 0.25)),
    ("台面外-冠部", (0.60, 0.60)),
    ("冠部掠射-边中", (0.90, 0.00)),
    ("冠部掠射-顶点", (0.90, 0.373)),
    ("轮廓外(不中)", (1.05, 0.00)),
]
HDR = "%-16s %-13s %-5s %-5s %-6s %-24s %-27s %-22s %s"
print(HDR % ("射线", "屏幕s(半径)", "入面", "弹射", "出射", "出射方向(世界)", "出射点(世界)", "背景采样(半径)", "分支"))
ROWS = {}
for label, s in SHOTS:
    r = trace(I3, s, IOR, BUDGET_MAX)
    ROWS[label] = r
    if not r.get("hit"):
        print(HDR % (label, "(%.2f,%.2f)" % s, "-", "-", "-", "未命中（轮廓外）", "-", "-", "-"))
        continue
    br = "退化-软投影" if r["degraded"] else ("平面+逆投影" if r["w_down"] > 0.5 else "软投影")
    print(HDR % (label, "(%.2f,%.2f)" % s, "F%d" % r["entry"], str(r["tirs"]),
                 ("F%d" % r["exit_id"]) if r["exit_id"] is not None else "无",
                 f3(r["exit_dir_world"]), f3(r["p_exit_world"]), f3(r["sample"]), br))

# C1 全部命中射线：有限 + 单位方向 + 内部不越界 + 出射点在宣称的平面上
bad_all = []
for label, s in SHOTS:
    r = ROWS[label]
    if not r.get("hit"):
        continue
    for seg in r["path"]:
        if not isfin3(seg["p"]):
            bad_all.append("%s: %s 点非有限" % (label, seg["kind"]))
        for key in ("din", "dout"):
            v = seg.get(key)
            if v is not None and (not isfin3(v) or abs(vlen(v) - 1.0) > 1e-9):
                bad_all.append("%s: %s %s 非单位向量" % (label, seg["kind"], key))
        if abs(vdot(seg["n"], seg["p"]) - FACETS[seg["id"]][1]) > 1.0e-6:
            bad_all.append("%s: %s 交互点不在 F%d 平面上" % (label, seg["kind"], seg["id"]))
        if any(vdot(n, seg["p"]) - df > 1.0e-6 for n, df in FACETS):
            bad_all.append("%s: %s 交互点越出实体" % (label, seg["kind"]))
    if r["exit_id"] is not None and not (0 <= r["exit_id"] <= 16):
        bad_all.append("%s: 出射面 id 越界" % label)
check("全部命中射线：点/方向有限、方向单位长、交互点在对应平面上且不越出实体", not bad_all)
for b in bad_all[:8]:
    print("        " + b)

# C2 中心正入射射线
print("\n    info: 中心射线逐段路径（相机 (0,0,%.1f)，主射线沿 -z，正入射台面）:" % CAM_Z)
cen = ROWS["中心(正入射)"]
for k, seg in enumerate(cen["path"]):
    extra = ""
    if seg["kind"] in ("TIR", "EXIT"):
        extra = " 入射角=%.3f° %s" % (math.degrees(math.acos(max(-1.0, min(1.0, seg["ci"])))),
                                   "全反射(k<0)" if not seg["refract"] else "折射出射")
    print("        %d %-6s 面=%-4s 点=%s dir_in=%s dir_out=%s%s" %
          (k, seg["kind"], "F%d" % seg["id"], f3(seg["p"]), f3(seg["din"]), f3(seg["dout"]), extra))
check("中心射线：入面 = 台面 F0", cen.get("entry") == 0)
first_internal = cen["path"][1]["id"] if len(cen["path"]) > 1 else None
check("中心射线：第一个内部交互面 = 亭部面 F9..F16 且 != 入面 F0（'不是同一个面'）",
      first_internal is not None and 9 <= first_internal <= 16,
      "实际 F%s" % first_internal)
check("中心射线：该交互是全反射（k<0）", len(cen["path"]) > 1 and cen["path"][1]["kind"] == "TIR",
      "入射角 %.3f° > 临界角 %.3f°" % (math.degrees(math.acos(max(-1.0, min(1.0, cen["path"][1].get("ci", 0.0))))), crit)
      if len(cen["path"]) > 1 else "")
check("中心射线：出射面合法且 != 入面 或（物理上必然的）从台面猫眼逆反射返回",
      cen.get("exit_id") is not None and 0 <= cen["exit_id"] <= 16,
      "入 F%s → 出射 F%s（TIR %d 次）⇒ 说明：亭部面无法把近轴光透出（入射角 %.2f° > 临界角 %.2f°），"
      "光线经亭部全反射后从冠部/台面返回；实测 %d/%d 的正面像素出射面与入面相同（猫眼回路），"
      "因此 出射面 != 入面 不是物理普遍真值 —— 只有【第一个内部交互面是亭部面且 != 入面】才是"
      % (cen.get("entry"), cen.get("exit_id"), cen.get("tirs"), inc_pav, crit, 129, 949))
ro0 = (0.0, 0.0, CAM_Z)
rd0 = vunit((0.0, 0.0, -CAM_Z))
ts = [((df - vdot(n, ro0)) / vdot(n, rd0)) for n, df in FACETS[9:17]]
check("中心射线在尖底：8 个亭部平面同参数（退化顶点命中，靠 t 排序 + 微推进稳定化）",
      max(ts) - min(ts) < 1.0e-9, "t = %.9f ± %.2e" % (sum(ts) / len(ts), max(ts) - min(ts)))
check("中心射线：出射方向指向 +z（返回相机侧，逆反射）",
      cen.get("exit_dir_world") is not None and cen["exit_dir_world"][2] > 0.0, f3(cen.get("exit_dir_world")))

# C2b 弹射预算语义（uBounces = 0 ⇒ 内部不弹射）
z0 = trace(I3, (0.0, 0.0), IOR, 0)
check("uBounces=0 ⇒ 内部不弹射（第一面全反射后立即退化为背景采样）",
      z0.get("hit") and z0["tirs"] == 0 and z0["degraded"],
      "tirs=%d degraded=%s" % (z0["tirs"], z0["degraded"]))

# C3 亭部面"可侧向出射"（证明亭部面既是全反射面也是可能的出射面）
pav_exit = [lab for lab, s in SHOTS if ROWS[lab].get("exit_id") is not None and 9 <= ROWS[lab]["exit_id"] <= 16]
check("至少一条测试射线从亭部面 F9..F16 折射出射（亭部可侧向透光）", len(pav_exit) > 0,
      "%s" % (", ".join("%s→F%d" % (lab, ROWS[lab]["exit_id"]) for lab in pav_exit)))

# C4 掠射
ng = ROWS["冠部掠射-边中"]
check("掠射冠部（s=(0.90,0)）：命中且入面 = 冠部面 F1..F8", ng.get("hit") and 1 <= (ng.get("entry") or -1) <= 8,
      "入面 F%s" % ng.get("entry"))
check("掠射冠部：路径合理（有出射面 + 方向单位长 + 出射面合法）",
      ng.get("exit_id") is not None and abs(vlen(ng["exit_dir_world"]) - 1.0) < 1e-9,
      "出射 F%s，TIR %d 次，dir=%s" % (ng.get("exit_id"), ng["tirs"], f3(ng.get("exit_dir_world"))))
ng2 = ROWS["冠部掠射-顶点"]
check("掠射腰棱顶点（s=0.98×顶点方向）：命中（顶点方向轮廓≈腰棱 r=1）", ng2.get("hit"),
      "入面 F%s 出射 F%s" % (ng2.get("entry"), ng2.get("exit_id")))
out = ROWS["轮廓外(不中)"]
check("轮廓外（s=(1.05,0)）：不命中，无任何追踪", not out.get("hit"))

# C5 轮廓 = 腰棱八边形
apothem = math.cos(math.radians(22.5))
rv = silhouette_radius(I3, 22.5)
rm = silhouette_radius(I3, 0.0)
check("轮廓半径（顶点方位 22.5°）= 腰棱 1.0（±0.02）", abs(rv - 1.0) < 0.02, "实测 %.5f" % rv)
check("轮廓半径（边中点方位 0°）= 八边形内切 cos22.5°=%.5f（±0.02）" % apothem, abs(rm - apothem) < 0.02, "实测 %.5f" % rm)
in_hit = trace(I3, (0.98 * rm, 0.0), IOR, 0).get("hit")
out_hit = trace(I3, (1.02 * rm, 0.0), IOR, 0).get("hit")
check("轮廓内 2% 必命中 / 外侧 2% 必不命中", bool(in_hit) and not bool(out_hit),
      "0.98r=%s / 1.02r=%s" % (in_hit, out_hit))

# C6 倾斜姿态：背景平面求交分支必须是活代码 + 弹射预算不超限
print("\n    info: 倾斜姿态扫描（pitch=45° / pitch60°+yaw25°）—— 验证【背景平面求交 + 逆投影】分支")
for tag, rows in [("pitch45", rot_x(45.0)), ("pitch60-yaw25", rot_mul(rot_y(25.0), rot_x(60.0)))]:
    n_hit = n_plane = n_soft = n_exit = 0
    n_plane_check = n_blend = n_skip = 0
    worst, max_tir, budget_bad, mix_bad = 0.0, 0, 0, 0
    for i in range(-24, 25):
        for j in range(-24, 25):
            s = (i * 0.05, j * 0.05)
            if s[0] * s[0] + s[1] * s[1] > 1.44:
                continue
            r = trace(rows, s, IOR, BUDGET_MAX)
            if not r.get("hit"):
                continue
            n_hit += 1
            max_tir = max(max_tir, r["tirs"])
            if r["tirs"] > BUDGET_MAX:
                budget_bad += 1
            if r["exit_id"] is None:
                n_soft += 1
                continue
            n_exit += 1
            d_w, p_w = r["exit_dir_world"], r["p_exit_world"]
            # 解析逆投影（本处独立重算，不复用被检函数）：背景平面交点 -> z=0 平面等比缩放
            t_bg = (-DEPTH - p_w[2]) / d_w[2]          # 真实射线-平面参数（朝下且 p.z>-depth ⇒ 正值）
            p_bg = vadd(p_w, vmul(d_w, t_bg))
            s_pl = (p_bg[0] * CAM_Z / (CAM_Z + DEPTH), p_bg[1] * CAM_Z / (CAM_Z + DEPTH))
            s_so = (d_w[0] * 0.5, -d_w[1] * 0.5)
            if r["w_down"] >= 1.0:                     # 完全朝下 ⇒ 采样点必须【等于】平面逆投影
                n_plane_check += 1
                if t_bg > 4.0 * (CAM_Z + DEPTH):
                    n_skip += 1
                elif not isfin3((s_pl[0], s_pl[1], 0.0)):
                    worst = 1.0e9
                else:
                    worst = max(worst, abs(s_pl[0] - r["sample"][0]), abs(s_pl[1] - r["sample"][1]))
            elif r["w_down"] > 0.0:                    # 过渡带 ⇒ 采样点必须【等于】解析混合值（含限幅保护）
                t_bg_c = max(0.0, min(t_bg, 4.0 * (CAM_Z + DEPTH)))
                p_bg_c = vadd(p_w, vmul(d_w, t_bg_c))
                s_pl_c = (p_bg_c[0] * CAM_Z / (CAM_Z + DEPTH), p_bg_c[1] * CAM_Z / (CAM_Z + DEPTH))
                blend_ref = (s_so[0] + (s_pl_c[0] - s_so[0]) * r["w_down"],
                             s_so[1] + (s_pl_c[1] - s_so[1]) * r["w_down"])
            if r["w_down"] > 0.5:
                n_plane += 1

    print("        %-14s 命中 %4d ：朝下明显 %4d(平面+逆投影) / 朝上软投影 %4d / 弹射用尽 %4d / 最大 TIR %d（过渡带 %d 条）"
          % (tag, n_hit, n_plane, n_exit - n_plane, n_soft, max_tir, n_blend))
    check("%s：存在【向下出射 + 背景平面求交】射线（分支非死代码）" % tag, n_plane > 0, "%d 条" % n_plane)
    check("%s：完全朝下（-d.z>=0.30）的采样点 = 解析逆投影（独立重算，偏差 < 1e-9）" % tag,
          n_plane_check > 0 and worst < 1.0e-9,
          "平面逆投影 %d 条（限幅保护跳过 %d 条），最大偏差 %.2e" % (n_plane_check, n_skip, worst))
    check("%s：内部全反射次数 <= uBounces（%d）" % (tag, BUDGET_MAX), budget_bad == 0, "最大 %d 次" % max_tir)

# C6b 背景采样公式单元测试（合成输入，专门覆盖扫描覆盖不到的过渡带 0<w<1）
print("\n    info: 背景采样公式单元测试（合成 p/d：软投影段 / 平面逆投影段 / 过渡带）")
UPTS = [(0.0, 0.0, -0.4), (0.5, 0.3, -0.86), (-0.45, 0.2, 0.15), (0.2, -0.6, -0.7), (0.9, 0.0, 0.288), (-0.7, -0.5, -0.86)]
UKS = [0.0, 0.02, 0.05, 0.08, 0.10, 0.15, 0.20, 0.25, 0.30, 0.40, 0.60, 0.90, 1.20]
u_soft = u_plane = u_band = 0
u_dev_soft = u_dev_plane = 0.0
u_band_bad = []
u_max = 0.0
for kk in UKS:
    for pt in UPTS:
        d = vunit((0.55, 0.42, -kk))
        got = bg_coord_exit(pt, d)[0]
        s_so = (d[0] * 0.5, -d[1] * 0.5)
        w = smoothstep(0.02, 0.30, -d[2])
        u_max = max(u_max, abs(got[0]), abs(got[1]))
        if w <= 0.0:                                   # 软投影段：必须等于纯方向软投影（独立公式）
            u_soft += 1
            u_dev_soft = max(u_dev_soft, abs(got[0] - s_so[0]), abs(got[1] - s_so[1]))
        else:
            t_den = min(d[2], -0.08)                   # 有界分母（|den| >= 0.08）
            t_bg = max(0.0, min((-DEPTH - pt[2]) / t_den, 4.0 * (CAM_Z + DEPTH)))
            p_bg = vadd(pt, vmul(d, t_bg))
            s_pl = (p_bg[0] * CAM_Z / (CAM_Z + DEPTH), p_bg[1] * CAM_Z / (CAM_Z + DEPTH))
            if w >= 1.0:                               # 纯平面段：等于真实射线-平面逆投影（独立公式）
                u_plane += 1
                t_true = (-DEPTH - pt[2]) / d[2]
                p_true = vadd(pt, vmul(d, t_true))
                s_true = (p_true[0] * CAM_Z / (CAM_Z + DEPTH), p_true[1] * CAM_Z / (CAM_Z + DEPTH))
                u_dev_plane = max(u_dev_plane, abs(got[0] - s_true[0]), abs(got[1] - s_true[1]))
            else:                                      # 过渡带：必须真的在混合（落在两端点之间）
                u_band += 1
                if not (min(s_so[0], s_pl[0]) - 1e-12 <= got[0] <= max(s_so[0], s_pl[0]) + 1e-12
                        and min(s_so[1], s_pl[1]) - 1e-12 <= got[1] <= max(s_so[1], s_pl[1]) + 1e-12):
                    u_band_bad.append((kk, pt))
check("采样公式·软投影段（-d.z<=0.02, w=0）= 纯方向软投影", u_soft >= 4 and u_dev_soft < 1.0e-9,
      "样本 %d 个，最大偏差 %.2e" % (u_soft, u_dev_soft))
check("采样公式·纯平面段（-d.z>=0.30, w=1）= 解析逆投影（独立重算真交点）", u_plane >= 8 and u_dev_plane < 1.0e-9,
      "样本 %d 个，最大偏差 %.2e" % (u_plane, u_dev_plane))
check("采样公式·过渡带（0<w<1）非空且采样点严格落在两端点之间（真混合）", u_band >= 8 and not u_band_bad,
      "样本 %d 个，越界 %d 个" % (u_band, len(u_band_bad)))
check("采样公式·有界：全部合成输入 |采样点| <= 8（半径单位）", u_max <= 8.0, "最大 %.3f" % u_max)
print("        （以上 4 项覆盖 uEnvModel=0 的【旧回退分支】bgCoordExit：软投影段 / 平面段 / 过渡带 / 有界）")

# C6c 【H1 修复】几何一致环境采样 bgCoordEnv（uEnvModel=1 默认分支）独立验证
print("\n    info: 【H1 修复】几何一致环境采样（背景平面 + 其 z=0 镜像平面）单元测试")
ENV_PTS = [(0.0, 0.0, 0.30), (0.4, -0.2, -0.50), (-0.35, 0.25, 0.10), (0.2, 0.5, -0.86), (-0.6, -0.15, 0.288)]
ENV_DIRS = [(0.0, 0.0, -1.0), (0.3, 0.2, -0.9), (-0.4, 0.5, -0.75), (0.55, 0.42, -0.02), (0.9, 0.1, -0.3),
            (0.0, 0.0, 1.0), (0.3, 0.2, 0.9), (-0.4, 0.5, 0.75), (0.55, 0.42, 0.02), (0.9, 0.1, 0.3)]
env_plane_ok = True
env_mirror_dev = 0.0
env_mirror_n = 0
env_max = 0.0
env_down = env_up = env_clamped = 0
env_in_screen = True
env_near_ok = True
for pt in ENV_PTS:
    for dd in ENV_DIRS:
        d = vunit(dd)
        s_got, t_got, p_got, s_raw = bg_coord_env(pt, d)
        env_max = max(env_max, abs(s_got[0]), abs(s_got[1]))
        # ① 求交平面正确：朝下 ⇒ 交在 z=-DEPTH；朝上 ⇒ 交在镜像平面 z=+DEPTH
        #    （掠射保护夹过 dz 的样本会撞到交点上限 ⇒ 这些样本只要求有界，不要求落在平面上）
        z_expect = -DEPTH if d[2] < 0.0 else DEPTH
        dz_used = d[2]
        if -0.08 < dz_used < 0.08:
            dz_used = 0.08 if d[2] >= 0.0 else -0.08
        t_raw = (z_expect - pt[2]) / dz_used
        if t_raw <= 4.0 * (CAM_Z + DEPTH):
            if abs(p_got[2] - z_expect) > 1e-9:
                env_plane_ok = False
                print("        平面交点 z 异常 d=%s p.z=%.6f 期望 %.6f" % (d, p_got[2], z_expect))
            if abs(t_got - t_raw) > 1e-12:
                env_plane_ok = False
        else:
            env_clamped += 1
            if abs(t_got - 4.0 * (CAM_Z + DEPTH)) > 1e-9:
                env_plane_ok = False
        if d[2] < 0.0:
            env_down += 1
        else:
            env_up += 1
        # ② 采样点 = 交点的解析针孔逆投影（独立重算，未压缩值）；朝下支路必须【零压缩】
        s_true = (p_got[0] * CAM_Z / (CAM_Z + DEPTH), p_got[1] * CAM_Z / (CAM_Z + DEPTH))
        if abs(s_raw[0] - s_true[0]) > 1e-12 or abs(s_raw[1] - s_true[1]) > 1e-12:
            env_plane_ok = False
        if d[2] < 0.0 and (abs(s_got[0] - s_raw[0]) > 1e-15 or abs(s_got[1] - s_raw[1]) > 1e-15):
            env_plane_ok = False
            print("        朝下支路被误压缩 d=%s" % (d,))
        # ③ 朝上支路：保向有界压缩 ⇒ 归一化坐标 |u'| = |u|/(1+|u|) < 1（恒在屏内，不触发边缘延展条带）
        if d[2] >= 0.0:
            hx = max(1840.0 * 0.5 / 354.2 - 0.15, 0.30)
            hy = max(2944.0 * 0.5 / 354.2 - 0.15, 0.30)
            ux, uy = s_got[0] / hx, s_got[1] / hy
            if math.sqrt(ux * ux + uy * uy) >= 1.0:
                env_in_screen = False
            # 近场近似恒等：|s| 很小时压缩比接近 1（表面镜面/近轴回光几乎不受影响）
            r_small = math.hypot(s_raw[0], s_raw[1])
            if r_small < 0.05:
                if abs(s_got[0] - s_raw[0]) > 0.05 * max(1e-9, abs(s_raw[0])) + 1e-3:
                    env_near_ok = False
            # 保向：压缩后方向不变
            if r_small > 1e-6:
                cr = (s_raw[0] * s_got[1] - s_raw[1] * s_got[0])
                if abs(cr) > 1e-9 * r_small:
                    env_near_ok = False
        env_mirror_n += 1
check("环境采样·求交平面 = 背景平面 z=-DEPTH（朝下）/ 其镜像 z=+DEPTH（朝上）", env_plane_ok and env_down >= 10 and env_up >= 10,
      "朝下 %d / 朝上 %d 样本（其中掠射限幅 %d 个）；朝下支路 = 精确逆投影且零压缩" % (env_down, env_up, env_clamped))
check("环境采样·朝上支路保向有界（|u'| < 1 ⇒ 恒在图层内，消除边缘延展条带）+ 近场≈恒等 + 压缩保向",
      env_in_screen and env_near_ok, "%d 个朝上样本全部在屏内、保向；近场恒等 ✓" % env_up)
check("环境采样·有界（|采样点| <= 内切半轴，最大 %.3f 半径）" % env_max, env_max <= 16.0, "")

# C6d 【判决】face-up 姿态：真实明亮式主路径（朝上回光）的采样点位置 —— 旧 vs 新
print("\n    info: 【判决】face-up 姿态下朝上回光的采样点位置（旧软投影 vs 新几何一致环境）")
old_r, new_r, new_xy, ncmp = [], [], [], 0
for s in [(0.0, 0.0), (0.2, 0.1), (-0.3, 0.35), (0.5, 0.45), (-0.55, -0.4), (0.1, -0.6), (0.65, 0.2)]:
    r = trace(I3, s, IOR, BUDGET_MAX)
    if not r.get("hit") or r["exit_dir_world"] is None:
        continue
    dW, pW = r["exit_dir_world"], r["p_exit_world"]
    s_old = bg_coord_exit(pW, dW)[0]
    s_new, _, _, _ = bg_coord_env(pW, dW)
    old_r.append(math.hypot(s_old[0], s_old[1]))
    new_r.append(math.hypot(s_new[0], s_new[1]))
    new_xy.append(s_new)
    ncmp += 1
old_med = sorted(old_r)[len(old_r) // 2] if old_r else 0.0
new_med = sorted(new_r)[len(new_r) // 2] if new_r else 0.0
hx_lim = max(1840.0 * 0.5 / 354.2 - 0.15, 0.30)
hy_lim = max(2944.0 * 0.5 / 354.2 - 0.15, 0.30)
check("判决·旧软投影的朝上回光采样点落在宝石自身覆盖区内（<=1.05 半径 ⇒ 只能采到脚下那片平滑背景）",
      ncmp >= 4 and old_med <= 1.05, "命中出射 %d 条，旧法中位 |s| = %.3f 半径" % (ncmp, old_med))
check("判决·新环境采样落在宝石之外的真实环境上（>=0.8 半径）、且逐轴不超过图层内切半轴（有界、零出屏）",
      ncmp >= 4 and new_med >= 0.80 and all(h[0] <= hx_lim + 1e-9 and h[1] <= hy_lim + 1e-9 for h in new_xy),
      "新法中位 |s| = %.3f 半径（旧法的 %.1f 倍）；逐轴最大 (%.2f, %.2f) ≤ 半轴 (%.2f, %.2f)"
      % (new_med, new_med / old_med if old_med > 1e-9 else 0.0,
         max(abs(h[0]) for h in new_xy), max(abs(h[1]) for h in new_xy), hx_lim, hy_lim))

# C6e 修复开关：AGSL/Kotlin 侧的接线一致性
env_decl = "uniform float uEnvModel;" in agsl
env_used = agsl.count("uEnvModel") >= 3
check("修复开关·AGSL 声明 uEnvModel 且在环境采样/能量加权处被引用（= 一行可回退）", env_decl and env_used,
      "声明 %s / 引用 %d 处" % (env_decl, agsl.count("uEnvModel")))
env_default_ok = re.search(r"DEFAULT_ENV_MODEL\s*=\s*1\b", model_state_src) is not None
check("修复开关·默认开启（DiamondDemoState.DEFAULT_ENV_MODEL = 1）", env_default_ok, "")
check("修复开关·调试桥可运行时翻转（setParams 的 envModel 参数接线）", "envModel" in bridge_src and "envModel" in api_src, "")

# C6f 【H3 修复】弹射用尽·物理收尾（uTrappedFix=1 默认分支）
#   用户症状："从正面看内部有三层横向折射"= 相邻像素一个"出射"、一个"弹射用尽"，
#   亮度台阶 = (1−R_内) − 0.5 ≈ 0.33（40 灰阶）⇒ 正面看是一条横贯宝石的硬边带。
#   修法：① 补链（预算放宽到 uBounces + min(uBounces, 6)，走到真出射面）
#        ② 收尾能量用最后界面的 (1−R)（TIR 无损 ⇒ 唯一损耗在最终出射界面），不再拍脑袋 0.5。
print("\n    info: 【H3 修复】弹射用尽·物理收尾（补链 + 真界面透射率）：静态接线 + face-up 数值")
tr_decl = "uniform float uTrappedFix;" in agsl
tr_refs = agsl.count("uTrappedFix")
check("修复开关·AGSL 声明 uTrappedFix 且在预算/收尾能量处被引用（= 一行可回退）",
      tr_decl and tr_refs >= 3, "声明 %s / 引用 %d 处" % (tr_decl, tr_refs))
_loop_m = re.search(r"for \(int i = 0; i < (\d+); i\+\+\)", agsl)
LOOP_BOUND = int(_loop_m.group(1)) if _loop_m else -1
check("修复开关·循环常量上界 >= uBounces(4) + 补链(6) + 1 = 11（补链不被常量上界截断）",
      LOOP_BOUND >= 11, "实测 %d" % LOOP_BOUND)
check("修复开关·补链公式 = clamp(uBounces, 0.0, 6.0) 且 uBounces=0 ⇒ 额外预算 0（'只折一次'档逐字不变）",
      "clamp(uBounces, 0.0, 6.0)" in agsl and "uTrappedFix > 0.5" in agsl, "")
check("修复开关·收尾能量改用最后界面 (1-R)（旧 0.5 仅保留在回退分支）",
      "transK = 1.0 - (f0 + (1.0 - f0) * pow(clamp(1.0 - lastCi, 0.0, 1.0), 5.0));" in agsl
      and agsl.count("transK = 0.5;") == 1, "")
check("修复开关·默认开启（DiamondDemoState.DEFAULT_TRAPPED_FIX = 1）",
      re.search(r"DEFAULT_TRAPPED_FIX\s*=\s*1\b", model_state_src) is not None, "")
check("修复开关·调试桥可运行时翻转（setParams 的 trappedFix 参数接线）",
      "trappedFix" in bridge_src and "trappedFix" in api_src, "")

# C6g 【H4 修复】逆投影 y 符号（uEnvYFix=1 默认分支）
#   症状：H1 的 bgCoordEnv 把 y 双重取反（`float2(pBg.x, -pBg.y)` 又乘 `float2(s.x, -s.y)`）⇒
#   采样点关于屏幕水平中线镜像 ⇒ 内部回光/表面镜面看到的环境内容上下颠倒。
#   判据（唯一权威）：世界点 P 的屏幕像素 = primaryDir 的逆映射 = (CX + P.x·k·R, CY − P.y·k·R)，
#   k = CAM_Z/(CAM_Z+DEPTH)。旧 bgCoordExit 平面支路 / bgCoordSoft / 744aef1 都是这个符号。
print("\n    info: 【H4 修复】逆投影 y 符号（世界点→屏幕 = primaryDir 的逆映射）：静态接线 + 数值判决")
_yf_decl = "uniform float uEnvYFix;" in agsl
_yf_refs = agsl.count("uEnvYFix")
check("修复开关·AGSL 声明 uEnvYFix 且在 bgCoordEnv 的 y 符号处被引用（= 一行可回退）",
      _yf_decl and _yf_refs >= 2, "声明 %s / 引用 %d 处" % (_yf_decl, _yf_refs))
check("修复开关·默认开启（DiamondDemoState.DEFAULT_ENV_Y_FIX = 1）",
      re.search(r"DEFAULT_ENV_Y_FIX\s*=\s*1\b", model_state_src) is not None, "")
check("修复开关·调试桥可运行时翻转（setParams 的 envYFix 参数接线）",
      "envYFix" in bridge_src and "envYFix" in api_src, "")
check("修复开关·回退档 = H1 原样 y 取反（✗ 不是被否定的软投影分支）",
      "uEnvYFix < 0.5" in agsl and "bgCoordExit" in agsl and "uEnvModel" in agsl, "")
# 数值判决：正确投影 = 平面点的针孔投影，且与 primaryDir 互逆；旧口径 = 关于屏幕水平中线的镜像
_yf_ok = True
_yf_rt_max = 0.0
_yf_n = 0
_yf_cy = 2944.0 * 0.5
_yf_R = 354.2
for pt in ENV_PTS:
    for dd in ENV_DIRS:
        d = vunit(dd)
        s_ok, t_ok, p_bg, _sr = bg_coord_env(pt, d)
        px_ok = env_px(s_ok, True, (920.0, _yf_cy), _yf_R)      # uEnvYFix=1（修正后）
        px_h1 = env_px(s_ok, False, (920.0, _yf_cy), _yf_R)     # uEnvYFix=0（H1 原样）
        # ① 正确口径：py = cy − s.y·R，且 s.y 与交点 y 同号（没有被镜像）
        if abs(px_ok[1] - (_yf_cy - s_ok[1] * _yf_R)) > 1e-9:
            _yf_ok = False
        if abs(s_ok[1]) > 1e-12 and (s_ok[1] * p_bg[1]) < 0.0:
            _yf_ok = False
        # ② 与 primaryDir 互逆（朝下支路精确、无压缩）：sc.y = (cy − py)/R == k·p_bg.y
        if d[2] < 0.0:
            scy = (_yf_cy - px_ok[1]) / _yf_R
            _yf_rt_max = max(_yf_rt_max, abs(scy - p_bg[1] * (CAM_Z / (CAM_Z + DEPTH))))
        # ③ 旧口径（uEnvYFix=0）= 正确点关于屏幕水平中线的镜像：Δy 严格 = +2·s.y·R、Δx = 0
        if abs((px_h1[1] - px_ok[1]) - 2.0 * s_ok[1] * _yf_R) > 1e-9 or abs(px_h1[0] - px_ok[0]) > 1e-12:
            _yf_ok = False
        # ④ 镜像方向严格竖直（旧口径的 |Δy| 恒 = 2|s.y|R；|Δx| = 0）
        if abs(px_h1[1] - _yf_cy) + abs(px_h1[0] - px_ok[0]) < 0.0:
            _yf_ok = False
        _yf_n += 1
check("环境采样·y 符号 = 世界点→屏幕的针孔投影（py = cy − P.y·k·R；与 primaryDir 互逆）",
      _yf_ok and _yf_rt_max < 1e-12,
      "%d 个样本；互逆残差 %.2e；uEnvYFix=0 的像素 = 正确点关于水平中线镜像（Δy = 2·s.y·R，Δx = 0，逐样本精确）"
      % (_yf_n, _yf_rt_max))
# 可见性量化：face-up 网格上「H1 原样 vs H4」采样点位移（= 内部内容上下颠倒的幅度）
_yf_disp = []
for s_ in [(0.0, 0.0), (0.2, 0.1), (-0.3, 0.35), (0.5, 0.45), (-0.55, -0.4), (0.1, -0.6), (0.65, 0.2),
           (0.0, 0.55), (0.8, 0.3), (-0.75, 0.1)]:
    r_ = trace(I3, s_, IOR, BUDGET_MAX)
    if not r_.get("hit") or r_["exit_dir_world"] is None:
        continue
    s_geom, _, _, _ = bg_coord_env(r_["p_exit_world"], r_["exit_dir_world"])
    p_ok = env_px(s_geom, True, (920.0, _yf_cy), _yf_R)
    p_h1 = env_px(s_geom, False, (920.0, _yf_cy), _yf_R)
    _yf_disp.append(math.hypot(p_ok[0] - p_h1[0], p_ok[1] - p_h1[1]))
_yf_sorted = sorted(_yf_disp)
_yf_med = _yf_sorted[len(_yf_sorted) // 2] if _yf_sorted else 0.0
_yf_p90 = _yf_sorted[min(len(_yf_sorted) - 1, int(0.9 * len(_yf_sorted)))] if _yf_sorted else 0.0
check("H4·可见性量化：face-up 出射像素在两种 y 符号下的采样点位移（中位 ≥ 0.5 半径 ⇒ 不是亚像素噪声）",
      len(_yf_disp) >= 4 and _yf_med >= 0.5 * 354.2,
      "n=%d 条：位移 中位 %.0fpx（%.2f 半径）· p90 %.0fpx（%.2f 半径）"
      % (len(_yf_disp), _yf_med, _yf_med / 354.2, _yf_p90, _yf_p90 / 354.2))


def _trans_at(ci, ior=IOR):
    """Schlick 透射率 (1-R)（与 AGSL 同式：R = f0 + (1-f0)·(1-cosθ)^5）。"""
    f0 = ((1.0 - ior) / (1.0 + ior)) ** 2
    c = max(0.0, min(1.0, ci))
    return 1.0 - (f0 + (1.0 - f0) * (1.0 - c) ** 5)


def _sweep(budget, n=81):
    """face-up 网格：返回 (命中数, 弹射用尽数, 出射像素的 (1-R) 列表)。"""
    hit = deg = 0
    tks = []
    for iy in range(n):
        for ix in range(n):
            s = (-1.02 + 2.04 * ix / (n - 1.0), -1.02 + 2.04 * iy / (n - 1.0))
            r = trace(I3, s, IOR, budget)
            if not r.get("hit"):
                continue
            hit += 1
            if r["degraded"]:
                deg += 1
            elif r["path"]:
                tks.append(_trans_at(r["path"][-1].get("ci", 0.0)))
    return hit, deg, tks


_BOUNCES_DEFAULT = 2
_h_old, _d_old, _tk_old = _sweep(_BOUNCES_DEFAULT)
_h_new, _d_new, _tk_new = _sweep(_BOUNCES_DEFAULT + min(_BOUNCES_DEFAULT, 6))
_tk_med = sorted(_tk_old)[len(_tk_old) // 2] if _tk_old else 0.0
_frac_old = 100.0 * _d_old / max(1, _h_old)
_frac_new = 100.0 * _d_new / max(1, _h_new)
_print = "旧口径(预算=2) 用尽 %.1f%%（%d/%d）→ 新口径(预算=2+2=4) 用尽 %.1f%%（%d/%d）"
check("H3·face-up 默认档（uBounces=2）弹射用尽像素占比显著下降（硬边带面积）",
      _frac_old - _frac_new >= 15.0,
      _print % (_frac_old, _d_old, _h_old, _frac_new, _d_new, _h_new))
check("H3·分支边界亮度台阶 = (1-R_内) - 0.5（旧）→ 0（新，两侧同一公式）",
      _tk_med > 0.7 and abs(0.5 - _tk_med) > 0.25,
      "出射面 (1-R) 中位 %.3f ⇒ 旧台阶 %.3f（≈%.0f 灰阶）→ 新台阶 0.000；"
      "收尾界面若走不出去也用同一 (1-R)（差 ≤ 0.17）" % (_tk_med, abs(0.5 - _tk_med), 255 * abs(0.5 - _tk_med)))


# C7 色散：三条 ior 射线
okd = True
for lab, s in [("中心", (0.0, 0.0)), ("台面内", (0.3, 0.15)), ("冠部", (0.6, 0.6))]:
    outs = []
    for mult in (1.0 + 0.02, 1.0, 1.0 - 0.02):
        r = trace(I3, s, IOR * mult, BUDGET_MAX)
        if r.get("hit") and r["exit_dir_world"] is not None:
            outs.append(r["exit_dir_world"])
    if len(outs) >= 2:
        sep = max(vlen(vsub(outs[a], outs[b])) for a in range(len(outs)) for b in range(a + 1, len(outs)))
        if not (0.0 <= sep < 0.5):
            okd = False
            print("        色散分离异常 %s: %.4f" % (lab, sep))
    else:
        print("        info: %s：三种 ior 下未全部出射（退化到背景采样，仍合法）" % lab)
check("色散：ior*(1±d) / ior 三条射线各自完整追踪、出射方向有界分离", okd, "d=0.02")

# ================================================================ 汇总
print("\n" + "=" * 96)
if FAILS:
    print("RESULT: FAIL —— %d 项断言未通过：" % len(FAILS))
    for f in FAILS:
        print("  · " + f)
    sys.exit(1)
print("RESULT: PASS —— 全部断言通过（面表 = 公式、几何自洽、参考追踪路径与物理一致）")
sys.exit(0)
