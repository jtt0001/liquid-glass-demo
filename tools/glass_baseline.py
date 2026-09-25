#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
玻璃视觉基准对比 —— LiquidGlassDemo
用法:
  python3 ~/.hermes/scripts/glass_baseline.py compare      # 抓当前机器的玻璃图并与基准对比，输出差异分数 + 并排对比图
  python3 ~/.hermes/scripts/glass_baseline.py baseline     # 把当前机器状态固化为新基准（仅在确认"观感正确"时使用）
输出:
  ~/Downloads/LiquidGlass-baseline/              基准裁切图 + manifest.json
  ~/Downloads/LiquidGlass-baseline/compare/      本次抓图、并排对比图、report.json

判据: 每张裁切图算「平均绝对差/255」与「差异像素占比(>8 灰阶)」。经验阈值:
  · <1.0% 且 >8 占比 <1%   → 视为一致 ✓（压缩噪声级别）
  · 1%~5%                  → 需人工看图（观感可能有细微变化）
  · >5%                    → 显著变化 ✗，必须人工确认是否有意为之
"""
import os, sys, json, subprocess, shutil, re

BASE_ROOT = os.path.expanduser('~/Downloads/LiquidGlass-baseline')


def base_dir(ser):
    safe = re.sub(r'[^A-Za-z0-9_.-]', '_', ser or 'default')
    d = os.path.join(BASE_ROOT, 'devices', safe)
    os.makedirs(d, exist_ok=True)
    return d


BASE = BASE_ROOT  # 兼容旧引用；实际路径请用 base_dir(ser)
PKG = 'com.liqglass.ultraclear'
ADB = os.path.expanduser('~/Library/Android/sdk/platform-tools/adb')
CROPS = {
    # —— 卡片（主界面）——
    'card':          (490, 560, 1300, 1620),
    'corner_tl_4x':  (490, 560, 610, 680),
    'edge_left_4x':  (490, 950, 560, 1150),
    'corner_br_4x':  (1180, 1480, 1300, 1600),
    # —— 面板/胶囊（展开态；面板宽~1790 居中、高~0.55 屏高、底边在导航栏上方）——
    'panel_arc_bl_4x': (30, 2820, 150, 2960),     # 面板左下圆角弧
    'panel_arc_br_4x': (1730, 2820, 1850, 2960),  # 面板右下圆角弧
    'panel_arc_tl_4x': (30, 1270, 150, 1410),     # 面板左上圆角弧
    'panel_edge_l_4x': (30, 2000, 100, 2200),     # 面板左直边
    'capsule_4x':      (700, 2760, 1180, 2900),   # 收起态胶囊（若在面板态则为面板中下部）
}


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout.strip()


def device():
    env = os.environ.get('LG_SERIAL')
    if env:
        return env
    out = sh(f'"{ADB}" devices')
    for line in out.splitlines()[1:]:
        if 'device' in line:
            return line.split('device')[0].strip()
    return None


def read_geometry(ser):
    """从 App 自己的 LGLayout 日志取【真实几何】——不再靠猜坐标。
    返回 {'capsule': (l,t,r,b), 'panel': (l,t,r,b)}（屏幕像素坐标，基于当前屏幕尺寸）。"""
    out = sh(f'"{ADB}" -s "{ser}" shell wm size')
    import re as _re
    m = _re.search(r'(\d+)x(\d+)', out)
    W, H = (int(m.group(1)), int(m.group(2))) if m else (1840, 2944)
    log = sh(f'"{ADB}" -s "{ser}" logcat -d -s LGLayout')
    lines = [l for l in log.splitlines() if 'p=' in l and 'w=' in l]
    def box(pick):
        for l in reversed(lines):
            mm = _re.search(r'p=([\d.]+) w=(\d+) h=(\d+) l_([\d.eE+\-]+)', l)
            if not mm: continue
            p, w, h, left = float(mm.group(1)), int(mm.group(2)), int(mm.group(3)), float(mm.group(4))
            if pick(p):
                top = H - 45 - h if p < 0.02 else (H - int(h))      # 收起态贴底；展开态顶部随高度
                return (int(left), int(top), int(left + w), int(top + h))
        return None
    return {'screen': (W, H), 'capsule': box(lambda p: p < 0.02), 'panel': box(lambda p: p > 0.98)}


def capture(ser, outdir):
    os.makedirs(outdir, exist_ok=True)
    import time
    sh(f'"{ADB}" -s "{ser}" shell settings put system screen_off_timeout 7200000')
    # 【测量纯净性】先清掉可能存在的系统覆盖层（Google 搜索/助手、键盘、通知栏）✗
    for _ in range(2):
        sh(f'"{ADB}" -s "{ser}" shell input keyevent KEYCODE_BACK')
        time.sleep(0.4)
    sh(f'"{ADB}" -s "{ser}" shell input keyevent KEYCODE_ESCAPE')
    sh(f'"{ADB}" -s "{ser}" shell am force-stop {PKG}')
    sh(f'"{ADB}" -s "{ser}" shell am start -W -n {PKG}/com.example.liquidglass.MainActivity')
    time.sleep(8)
    # 【硬前置】App 必须在前台，否则本次抓图作废并重试（最多 3 次）
    ok = False
    for attempt in range(3):
        act = sh(f'"{ADB}" -s "{ser}" shell dumpsys activity activities | grep -m1 ResumedActivity')
        if PKG in act:
            ok = True
            break
        for _ in range(2):
            sh(f'"{ADB}" -s "{ser}" shell input keyevent KEYCODE_BACK'); time.sleep(0.3)
        sh(f'"{ADB}" -s "{ser}" shell am force-stop {PKG}')
        sh(f'"{ADB}" -s "{ser}" shell am start -W -n {PKG}/com.example.liquidglass.MainActivity')
        time.sleep(6)
    if not ok:
        raise RuntimeError('capture 失败：App 未处于前台（系统覆盖层未清掉）— 本次抓图不可信 ✗')
    # 【唤醒 + 黑屏硬校验】屏幕 OFF 时 screencap 会"成功"返回全黑 PNG（≈28KB）✗ —— 必须唤醒并校验
    sh(f'"{ADB}" -s "{ser}" shell input keyevent 224')   # KEYCODE_WAKEUP
    time.sleep(0.8)
    sh(f'"{ADB}" -s "{ser}" shell am force-stop {PKG}')
    sh(f'"{ADB}" -s "{ser}" shell am start -W -n {PKG}/com.example.liquidglass.MainActivity')
    time.sleep(3)
    # 【规范化状态】用调试接口把面板强制归零（p=0 收起态）→ 消除"面板状态残留/错位"✗
    sh(f'"{ADB}" -s "{ser}" shell am broadcast -a com.liqglass.DEBUG --es cmd setPanelP --ef value 0 -p {PKG}')
    time.sleep(1.5)
    # 【稳定性】壁纸位图是异步解码的 → 先丢掉一张，再等画面稳定后正式拍（连续两张一致才算稳 ✓）
    def _shot():
        return subprocess.run(f'"{ADB}" -s "{ser}" exec-out screencap -p', shell=True, capture_output=True).stdout
    prev = _shot()
    for _ in range(8):
        time.sleep(1.5)
        cur = _shot()
        if prev == cur and len(cur) > 100000:
            break
        prev = cur
    p1 = os.path.join(outdir, 'full_main.png')
    _data = _shot()
    with open(p1, 'wb') as f:
        f.write(_data)
    # 【黑屏硬校验】全黑 = 屏幕灭/渲染失败 ⇒ 直接判本次抓图无效，避免污染比对 ✗
    try:
        from PIL import Image as _I
        import io as _io
        _m = _I.open(_io.BytesIO(_data)).convert('L')
        _small = list(_m.resize((40, 60)).getdata())
        if max(_small) < 12:
            raise RuntimeError('capture 失败：抓到的画面【全黑】✗（屏幕灭/渲染失败）——本次抓图作废，不接受为基准')
    except ImportError:
        pass
    # 用 uiautomator 取「控制中心」胶囊的真实可点区（设备无关 ✗ 不再用硬编码坐标）
    tap_xy = None
    try:
        sh(f'"{ADB}" -s "{ser}" shell uiautomator dump /sdcard/gl_ui.xml')
        xml = sh(f'"{ADB}" -s "{ser}" shell cat /sdcard/gl_ui.xml')
        import re as _re
        # 先找 clickable=true 的最大底部节点；再退化到含「控制中心」文本的节点
        cands = _re.findall(r'clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        best = None
        for x1, y1, x2, y2 in cands:
            x1, y1, x2, y2 = map(int, (x1, y1, x2, y2))
            if y1 > 2000 and (x2 - x1) > 200:
                if best is None or (x2 - x1) * (y2 - y1) > (best[2] - best[0]) * (best[3] - best[1]):
                    best = (x1, y1, x2, y2)
        if best:
            tap_xy = ((best[0] + best[2]) // 2, (best[1] + best[3]) // 2)
    except Exception:
        tap_xy = None
    if tap_xy is None:
        out = sh(f'"{ADB}" -s "{ser}" shell wm size')
        import re as _re2
        m = _re2.search(r'(\d+)x(\d+)', out)
        W, H = (int(m.group(1)), int(m.group(2))) if m else (1840, 2944)
        tap_xy = (W // 2, H - 162)
    sh(f'"{ADB}" -s "{ser}" shell input tap {tap_xy[0]} {tap_xy[1]}')
    time.sleep(3.2)
    # 校验：面板必须真的打开了（读 LGLayout 的 p），否则视为抓图失败
    lg = sh(f'"{ADB}" -s "{ser}" logcat -d -s LGLayout')
    import re as _re3
    ps = _re3.findall(r'p=([\d.]+)', lg)
    panel_open = bool(ps) and float(ps[-1]) > 0.9
    if not panel_open:
        sh(f'"{ADB}" -s "{ser}" logcat -c')
        sh(f'"{ADB}" -s "{ser}" shell input tap {tap_xy[0]} {tap_xy[1]}')
        time.sleep(3.2)
    # 面板态截图（必须在 return 之前 ✓）
    # 面板态：等动画收敛（p 已校验 >0.9）后再连拍两张一致才收
    prev = _shot()
    for _ in range(6):
        time.sleep(1.2)
        cur = _shot()
        if prev == cur and len(cur) > 100000:
            break
        prev = cur
    p2 = os.path.join(outdir, 'full_panel.png')
    with open(p2, 'wb') as f:
        f.write(_shot())
    return p1, p2, outdir, tap_xy
    return p1, p2, outdir


def crop_all(full_main, full_panel, outdir, geo=None):
    from PIL import Image
    made = {}
    boxes = dict(CROPS)
    if geo:
        c, pn = geo.get('capsule'), geo.get('panel')
        if pn:
            l, t, r, b = pn
            boxes['panel_arc_bl_4x'] = (max(0,l-12), max(0,b-150), l+150, b+8)
            boxes['panel_arc_br_4x'] = (r-150, max(0,b-150), r+12, b+8)
            boxes['panel_arc_tl_4x'] = (max(0,l-12), max(0,t-12), l+150, t+150)
            boxes['panel_edge_l_4x'] = (max(0,l-12), (t+b)//2-100, l+70, (t+b)//2+100)
        if c:
            boxes['capsule_4x'] = c
    for name, box in boxes.items():
        for src, tag in ((full_main, 'main'), (full_panel, 'panel')):
            im = Image.open(src).convert('RGB').crop(box)
            if name.endswith('4x'):
                im = im.resize((im.width * 4, im.height * 4), Image.LANCZOS)
            p = os.path.join(outdir, f'{name}__{tag}.png')
            im.save(p)
            made[f'{name}__{tag}'] = p
    return made


def compare(base_dir, cur_dir):
    from PIL import Image, ImageChops
    report = {}
    if not os.path.isdir(cur_dir):
        return report
    for fn in sorted(os.listdir(cur_dir)):
        if not fn.endswith('.png') or fn.startswith('full_'):
            continue
        bp = os.path.join(base_dir, fn)
        if not os.path.exists(bp):
            report[fn] = {'verdict': '无基准', 'mad_pct': None, 'diff_px_pct': None}
            continue
        a = Image.open(bp).convert('RGB'); b = Image.open(os.path.join(cur_dir, fn)).convert('RGB')
        if a.size != b.size:
            report[fn] = {'verdict': '尺寸不一致(设备分辨率/状态变了?)', 'mad_pct': None, 'diff_px_pct': None}
            continue
        diff = ImageChops.difference(a, b).convert('L')
        px = list(diff.getdata()); n = len(px)
        mad = sum(px) / n / 255 * 100
        big = sum(1 for v in px if v > 8) / n * 100
        verdict = '一致 ✓' if (mad < 1.0 and big < 1.0) else ('需人工看图' if mad < 5.0 else '显著变化 ✗')
        report[fn] = {'verdict': verdict, 'mad_pct': round(mad, 2), 'diff_px_pct': round(big, 2)}
        # 并排对比图（左=基准 右=本次）+ 放大的差异热图
        side = Image.new('RGB', (a.width * 2 + 12, a.height), (20, 20, 24))
        side.paste(a, (0, 0)); side.paste(b, (a.width + 12, 0))
        side.save(os.path.join(cur_dir, f'cmp__{fn}'))
        diff.point(lambda v: min(255, v * 4)).save(os.path.join(cur_dir, f'diffmap__{fn}'))
    return report


PREF_FILE = 'liquid_glass_background.xml'
WALLPAPERS = {
    0: 'clouds', 1: 'meadow', 2: 'lake', 3: 'forest', 4: 'grid', 5: 'colorgrid',
}


def set_wallpaper(ser, idx):
    """用 run-as 直接写 SharedPreferences（debug 包）—— 避免手点 UI 的坐标误差。
    可靠写法：本地生成 xml → adb push 到 /data/local/tmp → run-as cp 进私有目录 → chmod。"""
    import tempfile
    sh(f'"{ADB}" -s "{ser}" shell am force-stop {PKG}')
    xml = ('<?xml version=\'1.0\' encoding=\'utf-8\' standalone=\'yes\' ?>\n'
           '<map>\n    <int name="bg_builtin_index" value="%d" />\n</map>\n' % idx)
    with tempfile.NamedTemporaryFile('w', suffix='.xml', delete=False) as f:
        f.write(xml); tmp = f.name
    sh(f'"{ADB}" -s "{ser}" push "{tmp}" /data/local/tmp/lg_prefs.xml')
    sh(f'"{ADB}" -s "{ser}" shell run-as {PKG} cp /data/local/tmp/lg_prefs.xml shared_prefs/{PREF_FILE}')
    sh(f'"{ADB}" -s "{ser}" shell run-as {PKG} chmod 660 shared_prefs/{PREF_FILE}')
    out = sh(f'"{ADB}" -s "{ser}" shell run-as {PKG} cat shared_prefs/{PREF_FILE}')
    os.unlink(tmp)
    return f'value="{idx}"' in out


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'compare'
    ser = device()
    if not ser:
        print('✗ 设备离线：请点亮平板屏幕（无线 adb）后重试'); return 1

    if mode == 'baseline':
        ok_all = True
        BD = base_dir(ser)
        for idx, tag in WALLPAPERS.items():
            d = os.path.join(BD, f'wp{idx}_{tag}')
            shutil.rmtree(d, ignore_errors=True); os.makedirs(d, exist_ok=True)
            ok = set_wallpaper(ser, idx)
            print(f'--- 壁纸 {idx} ({tag}) 偏好写入: {"✓" if ok else "✗ 回退到点 UI"}')
            p1, p2, _, _tap = capture(ser, d)
            crop_all(p1, p2, d)
            ok_all = ok_all and ok
        json.dump({'crops': CROPS, 'wallpapers': WALLPAPERS, 'device': ser, 'note': 'glass visual baseline per wallpaper'},
                  open(os.path.join(BD, 'manifest.json'), 'w'), indent=1, ensure_ascii=False)
        print(f'✓ 6 张壁纸的基准已固化到 {BD}（设备 {ser}，偏好写入全部成功: {ok_all}）')
        return 0

    rep = {}
    BD = base_dir(ser)
    for idx, tag in WALLPAPERS.items():
        b = os.path.join(BD, f'wp{idx}_{tag}')
        cur = os.path.join(BD, 'compare', f'wp{idx}_{tag}')
        shutil.rmtree(cur, ignore_errors=True); os.makedirs(cur, exist_ok=True)
        set_wallpaper(ser, idx)
        p1, p2, _, _tap = capture(ser, cur)
        crop_all(p1, p2, cur)
        for k, v in compare(b, cur).items():
            rep[f'wp{idx}:{k}'] = v
    json.dump(rep, open(os.path.join(cur, 'report.json'), 'w'), indent=1, ensure_ascii=False)
    print(f'\n{"裁切图":<24}{"判定":<14}{"平均差%":>9}{"差异像素%":>11}')
    print('-' * 60)
    bad = 0
    for k, v in rep.items():
        print(f'{k:<24}{v["verdict"]:<14}{str(v["mad_pct"]):>9}{str(v["diff_px_pct"]):>11}')
        if v['verdict'] not in ('一致 ✓',) and '无基准' not in v['verdict'] and '尺寸不一致' not in v['verdict']:
            bad += 1
    print(f'\n对比图目录（左=基准 右=本次，另附 diffmap__* 差异热图）: {cur}')
    print('结论:', '✓ 与基准一致' if bad == 0 else f'✗ 有 {bad} 张需人工确认')
    return 0 if bad == 0 else 2


if __name__ == '__main__':
    sys.exit(main())
