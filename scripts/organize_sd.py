#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
碧蓝航线 SD 小人资源整理脚本
============================

将 d:\\Android\\Project\\blyy\\sd 下散落在 TextAsset/ 和 Texture2D/ 的 Spine 三件套
(.skel + .atlas + .png) 按舰娘拼音主名 → 皮肤 二级分类归档到 organized/ 目录，
并生成 manifest.json 资源清单供 App 读取。

目录规范（整理后，复制到手机 Download/BLYY/blhx_sd/）:

    organized/
    ├── boge/                       # 舰娘拼音主名（统一小写）
    │   ├── default/                # 默认皮肤
    │   │   ├── boge.skel
    │   │   ├── boge.atlas
    │   │   └── boge.png
    │   ├── gai/                    # 改造皮肤（_g 后缀）
    │   │   ├── boge_g.skel
    │   │   ├── boge_g.atlas        # 无独立 atlas 时从 default 复制并改写首行
    │   │   └── boge_g.png
    │   └── skin2/                  # 换皮2（_2 后缀）
    │       ├── he_2.skel
    │       └── ...
    ├── z23/
    │   └── default/
    │       └── ...
    └── manifest.json

皮肤后缀识别规则（提取主名时去除）:
    _2 _3 ... _9   换皮编号 → skin2 / skin3 / ...
    _g             改造     → gai
    _h             换装     → huan
    _hx            幻象     → huangxiang
    _L _R          左右半身（立绘，非 SD，默认跳过）
    _doa           死或生联动
    _y             特别计划 → teyao

Atlas 共享策略:
    皮肤缺独立 .atlas 时，从 default 皮肤复制 atlas 并改写首行引用该皮肤的 .png，
    确保 SpineSdView 的主查找路径 $dirPath/$assetName.atlas 命中，无需修改 App 端代码。

用法:
    python organize_sd.py                      # 默认整理到 sd/organized/
    python organize_sd.py --dry-run            # 只预览不复制
    python organize_sd.py --src <path>         # 指定源目录
    python organize_sd.py --out <path>         # 指定输出目录
    python organize_sd.py --clean              # 清空输出目录后重新整理
    python organize_sd.py --include-lr         # 包含 _L/_R 半身立绘
    python organize_sd.py --gui                # 启动 Web 图形界面

增量模式: 重复运行只复制新增/修改的文件，安全可重复执行。
"""

import argparse
import json
import os
import re
import shutil
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

# 皮肤后缀正则：匹配 _数字 / _g / _h / _hx / _L / _R / _doa / _y
SKIN_SUFFIX_RE = re.compile(r'^(.+?)(?:_([0-9]+|g|h|hx|L|R|doa|y))?$', re.IGNORECASE)

# 通用舰种 SD 前缀（srBB/srCA/srCL/srCV/srDD/srSS），数字直接接在后面无下划线
SR_PREFIX_RE = re.compile(r'^(sr(?:BB|CA|CL|CV|DD|SS))(?:([0-9]+|_R))?$', re.IGNORECASE)

# 支持的资源扩展名
SKEL_EXT = '.skel'
ATLAS_EXT = '.atlas'
PNG_EXT = '.png'

# 跳过的非舰娘资源（工具图标等）
SKIP_NAMES = {'loader', 'redcar'}

# 皮肤后缀 → 分类名映射
SKIN_SUFFIX_MAP = {
    'g': 'gai',        # 改造
    'h': 'huan',       # 换装
    'hx': 'huangxiang',# 幻象
    'doa': 'doa',      # 死或生联动
    'y': 'teyao',      # 特别计划
    'l': 'left',       # 左半身（默认跳过）
    'r': 'right',      # 右半身（默认跳过）
}


def extract_base_name(filename: str) -> str:
    """从文件名提取舰娘拼音主名（去除扩展名和皮肤后缀，统一小写）。

    统一小写是因为 App 端 PinyinHelper.toPinyin() 始终输出小写拼音，
    目录名也需小写才能匹配。避免 Z1/ 与 z1/ 在大小写敏感的 Android
    文件系统上成为两个不同目录导致匹配失败。

    例:
        boge.skel -> boge
        boge_g.png -> boge
        z23_2.skel -> z23
        srBB0.png -> srbb
        Z1_2.atlas -> z1
    """
    name = _strip_ext(filename)
    m = SR_PREFIX_RE.match(name)
    if m:
        return m.group(1).lower()
    m = SKIN_SUFFIX_RE.match(name)
    base = m.group(1) if m else name
    return base.lower()


def classify_skin(filename: str) -> str:
    """识别皮肤类型，返回 'default' / 'gai' / 'skin2' / 'huan' 等。"""
    name = _strip_ext(filename)
    m = SR_PREFIX_RE.match(name)
    if m:
        suffix = m.group(2)
        if suffix is None:
            return 'default'
        if suffix == '_R' or suffix.upper() == 'R':
            return 'mirror'
        return f'skin{suffix}'

    m = SKIN_SUFFIX_RE.match(name)
    if not m or m.group(2) is None:
        return 'default'
    suffix = m.group(2).lower()
    if suffix in SKIN_SUFFIX_MAP:
        return SKIN_SUFFIX_MAP[suffix]
    if suffix.isdigit():
        return f'skin{suffix}'
    return 'default'


def _strip_ext(filename: str) -> str:
    """去除文件扩展名（大小写不敏感）。"""
    name = filename
    for ext in (SKEL_EXT, ATLAS_EXT, PNG_EXT):
        if name.lower().endswith(ext):
            name = name[:-len(ext)]
            break
    return name


def _file_ext(filename: str) -> str:
    """返回扩展名类型: 'skel' / 'atlas' / 'png' / ''"""
    lower = filename.lower()
    if lower.endswith(SKEL_EXT): return 'skel'
    if lower.endswith(ATLAS_EXT): return 'atlas'
    if lower.endswith(PNG_EXT): return 'png'
    return ''


def scan_source(src_dir: Path, include_lr: bool) -> dict:
    """扫描源目录，按 (舰娘, 皮肤) 二级分组。

    皮肤由 .skel 文件定义——只有存在 .skel 的皮肤才会被创建。
    .atlas / .png 文件按后缀匹配到对应皮肤；若匹配的皮肤无 .skel（孤儿文件），
    则归入 'default' 皮肤的 extra_pngs / extra_atlases。

    Returns:
        {base_name: {skin_name: {
            'skel': Path | None,
            'atlas': Path | None,
            'png': Path | None,
            'extra_pngs': [Path],
        }}}
    """
    text_asset_dir = src_dir / 'TextAsset'
    texture2d_dir = src_dir / 'Texture2D'

    search_dirs = []
    if text_asset_dir.exists():
        search_dirs.append(text_asset_dir)
    if texture2d_dir.exists():
        search_dirs.append(texture2d_dir)
    if not search_dirs:
        search_dirs.append(src_dir)

    all_files = []
    for d in search_dirs:
        for entry in d.iterdir():
            if entry.is_file():
                all_files.append(entry)

    # 两次扫描：第一次注册皮肤（.skel），第二次分配 atlas/png
    ships: dict = defaultdict(lambda: defaultdict(lambda: {
        'skel': None, 'atlas': None, 'png': None, 'extra_pngs': []
    }))

    # Pass 1: 注册皮肤（每个 .skel 定义一个皮肤）
    for filepath in all_files:
        filename = filepath.name
        ext = _file_ext(filename)
        if ext != 'skel':
            continue
        base = extract_base_name(filename)
        if not base or base.lower() in SKIP_NAMES:
            continue
        skin = classify_skin(filename)
        if skin in ('left', 'right') and not include_lr:
            continue
        ships[base][skin]['skel'] = filepath

    # Pass 2: 分配 .atlas 和 .png 到皮肤
    for filepath in all_files:
        filename = filepath.name
        ext = _file_ext(filename)
        if ext not in ('atlas', 'png'):
            continue
        base = extract_base_name(filename)
        if not base or base.lower() in SKIP_NAMES:
            continue
        skin = classify_skin(filename)
        if skin in ('left', 'right') and not include_lr:
            continue

        ship_skins = ships[base]
        # 若该皮肤已有 .skel，直接分配
        if skin in ship_skins and ship_skins[skin]['skel'] is not None:
            if ext == 'atlas':
                if ship_skins[skin]['atlas'] is None:
                    ship_skins[skin]['atlas'] = filepath
            elif ext == 'png':
                if ship_skins[skin]['png'] is None:
                    ship_skins[skin]['png'] = filepath
                else:
                    ship_skins[skin]['extra_pngs'].append(filepath)
        else:
            # 孤儿文件：归入 default
            default = ship_skins['default']
            if ext == 'atlas':
                if default['atlas'] is None:
                    default['atlas'] = filepath
            elif ext == 'png':
                if default['png'] is None:
                    default['png'] = filepath
                else:
                    default['extra_pngs'].append(filepath)

    # 清理：移除没有 .skel 的皮肤（将其文件合并到 default）
    for base, skins in list(ships.items()):
        for skin_name in list(skins.keys()):
            if skins[skin_name]['skel'] is None and skin_name != 'default':
                orphan = skins[skin_name]
                default = skins['default']
                if orphan['atlas'] and default['atlas'] is None:
                    default['atlas'] = orphan['atlas']
                if orphan['png'] and default['png'] is None:
                    default['png'] = orphan['png']
                elif orphan['png']:
                    default['extra_pngs'].append(orphan['png'])
                del skins[skin_name]

    # 移除完全空的舰娘
    ships = {base: skins for base, skins in ships.items()
             if any(s['skel'] for s in skins.values())}

    return ships


def adapt_atlas_for_skin(src_atlas: Path, skin_png_name: str, dst_atlas: Path):
    """复制 atlas 文件并改写首行引用为皮肤专属 .png。

    Spine atlas 文件第一行是 png 文件名，TextureAtlas 从 atlas 文件同级目录加载该 png。
    改写首行后，皮肤目录中的 `<skin>.png` 即可被正确加载，实现皮肤独立贴图。
    """
    with open(src_atlas, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    # 找到第一个非空行（png 引用行）并替换
    for i, line in enumerate(lines):
        if line.strip():
            lines[i] = skin_png_name + '\n'
            break

    dst_atlas.parent.mkdir(parents=True, exist_ok=True)
    with open(dst_atlas, 'w', encoding='utf-8') as f:
        f.writelines(lines)


def _copy_file(src: Path, dst: Path, dry_run: bool) -> bool:
    """复制文件（增量模式：已存在且同大小则跳过）。返回 True=已复制，False=跳过。"""
    if dst.exists() and dst.stat().st_size == src.stat().st_size:
        return False
    if not dry_run:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    return True


def copy_groups(groups: dict, out_dir: Path, dry_run: bool, clean: bool) -> dict:
    """复制分组文件到 <ship>/<skin>/ 二级子目录结构。

    Atlas 共享策略:
        - 皮肤有独立 atlas → 原样复制
        - 皮肤无 atlas 但 default 有 → 复制 default atlas 并改写首行引用皮肤 png
        - 皮肤也无 png → 复制 default atlas（不改写）+ default png

    Returns: {'copied': int, 'skipped': int, 'ships': int, 'skins': int}
    """
    if clean and out_dir.exists() and not dry_run:
        shutil.rmtree(out_dir)
    if not dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)

    stats = {'copied': 0, 'skipped': 0, 'ships': 0, 'skins': 0}

    for base_name, skins in sorted(groups.items()):
        stats['ships'] += 1
        default_files = skins.get('default', {})
        default_atlas = default_files.get('atlas')
        default_png = default_files.get('png')

        for skin_name, files in sorted(skins.items()):
            skel = files['skel']
            if skel is None:
                continue
            stats['skins'] += 1
            skin_dir = out_dir / base_name / skin_name
            skin_asset_name = skel.stem  # e.g. "he_2" / "boge"

            # 1. 复制 .skel（始终保留原文件名）
            dst = skin_dir / skel.name
            if _copy_file(skel, dst, dry_run):
                stats['copied'] += 1
            else:
                stats['skipped'] += 1

            # 2. 处理 atlas + png
            atlas = files['atlas']
            png = files['png']

            if atlas:
                # 皮肤有独立 atlas：原样复制
                dst = skin_dir / atlas.name
                if _copy_file(atlas, dst, dry_run):
                    stats['copied'] += 1
                else:
                    stats['skipped'] += 1
                # 复制皮肤 png
                if png:
                    dst = skin_dir / png.name
                    if _copy_file(png, dst, dry_run):
                        stats['copied'] += 1
                    else:
                        stats['skipped'] += 1
            elif default_atlas:
                # 无独立 atlas：从 default 复制并改写
                dst_atlas = skin_dir / f"{skin_asset_name}.atlas"
                if png:
                    # 改写 atlas 首行引用皮肤 png
                    if dry_run:
                        stats['copied'] += 1
                    else:
                        adapt_atlas_for_skin(default_atlas, png.name, dst_atlas)
                        stats['copied'] += 1
                    # 复制皮肤 png
                    dst_png = skin_dir / png.name
                    if _copy_file(png, dst_png, dry_run):
                        stats['copied'] += 1
                    else:
                        stats['skipped'] += 1
                elif default_png:
                    # 皮肤无 png：复制 default atlas（不改写）+ default png
                    if _copy_file(default_atlas, dst_atlas, dry_run):
                        stats['copied'] += 1
                    else:
                        stats['skipped'] += 1
                    dst_png = skin_dir / default_png.name
                    if _copy_file(default_png, dst_png, dry_run):
                        stats['copied'] += 1
                    else:
                        stats['skipped'] += 1
            else:
                # 无 atlas 资源：仅复制 png
                if png:
                    dst = skin_dir / png.name
                    if _copy_file(png, dst, dry_run):
                        stats['copied'] += 1
                    else:
                        stats['skipped'] += 1

            # 3. 复制额外 png（孤儿贴图，保留但不被 atlas 引用）
            for extra_png in files.get('extra_pngs', []):
                dst = skin_dir / extra_png.name
                if _copy_file(extra_png, dst, dry_run):
                    stats['copied'] += 1
                else:
                    stats['skipped'] += 1

    return stats


def build_manifest(groups: dict) -> dict:
    """构建 manifest.json 数据结构（v2，含皮肤子目录信息）。"""
    ships = {}
    skin_total = 0

    for base_name in sorted(groups.keys()):
        skins = groups[base_name]
        skin_entries = {}
        for skin_name, files in sorted(skins.items()):
            if files['skel'] is None:
                continue
            skin_total += 1
            skin_entries[skin_name] = {
                'dir': skin_name,
                'skel': files['skel'].name,
                'atlas': files['atlas'].name if files['atlas'] else None,
                'png': files['png'].name if files['png'] else None,
                'extra_pngs': [p.name for p in files.get('extra_pngs', [])],
            }
        if skin_entries:
            ships[base_name] = {'skins': skin_entries}

    return {
        'version': 2,
        'generated_at': datetime.now().isoformat(timespec='seconds'),
        'ship_count': len(ships),
        'skin_count': skin_total,
        'ships': ships,
    }


def write_manifest(manifest: dict, out_dir: Path, dry_run: bool):
    """写入 manifest.json。"""
    manifest_path = out_dir / 'manifest.json'
    if dry_run:
        print(f'  [DRY] manifest.json ({manifest["ship_count"]} ships, {manifest["skin_count"]} skins)')
    else:
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        with open(manifest_path, 'w', encoding='utf-8') as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
        print(f'已生成: {manifest_path} ({manifest["ship_count"]} 个舰娘, {manifest["skin_count"]} 个皮肤)')


def run_organize(src_dir: Path, out_dir: Path, dry_run: bool, clean: bool, include_lr: bool) -> dict:
    """执行完整整理流程，返回结果摘要（供 GUI 调用）。"""
    if not src_dir.exists():
        return {'error': f'源目录不存在: {src_dir}'}

    groups = scan_source(src_dir, include_lr=include_lr)
    manifest = build_manifest(groups)
    stats = copy_groups(groups, out_dir, dry_run, clean)
    if not dry_run:
        write_manifest(manifest, out_dir, dry_run)

    return {
        'manifest': manifest,
        'stats': stats,
        'out_dir': str(out_dir),
    }


def launch_gui():
    """启动 Web 图形界面。"""
    try:
        from organize_sd_gui import main as gui_main
    except ImportError:
        print('错误: GUI 模块未找到，请确保 organize_sd_gui.py 在同一目录', file=sys.stderr)
        sys.exit(1)
    gui_main()


def main():
    parser = argparse.ArgumentParser(description='碧蓝航线 SD 小人资源整理脚本')
    parser.add_argument('--src', default=None, help='源目录（默认脚本同级的 sd/ 目录）')
    parser.add_argument('--out', default=None, help='输出目录（默认 <src>/organized/）')
    parser.add_argument('--dry-run', action='store_true', help='只预览不实际复制')
    parser.add_argument('--clean', action='store_true', help='清空输出目录后重新整理')
    parser.add_argument('--include-lr', action='store_true', help='包含 _L/_R 半身立绘（默认跳过）')
    parser.add_argument('--gui', action='store_true', help='启动 Web 图形界面')
    args = parser.parse_args()

    if args.gui:
        launch_gui()
        return

    script_dir = Path(__file__).resolve().parent
    project_root = script_dir.parent
    src_dir = Path(args.src) if args.src else project_root / 'sd'
    out_dir = Path(args.out) if args.out else src_dir / 'organized'

    if not src_dir.exists():
        print(f'错误: 源目录不存在: {src_dir}', file=sys.stderr)
        sys.exit(1)

    print(f'源目录: {src_dir}')
    print(f'输出目录: {out_dir}')
    print(f'模式: {"预览" if args.dry_run else "实际复制"}{" (清空重建)" if args.clean else " (增量)"}')
    print()

    # 1. 扫描
    print('扫描源文件...')
    groups = scan_source(src_dir, include_lr=args.include_lr)
    print(f'发现 {len(groups)} 个舰娘资源组')

    total_skel = sum(1 for skins in groups.values() for f in skins.values() if f['skel'])
    total_atlas = sum(1 for skins in groups.values() for f in skins.values() if f['atlas'])
    total_png = sum(1 for skins in groups.values() for f in skins.values() if f['png'])
    total_skins = sum(len([s for s in skins.values() if s['skel']]) for skins in groups.values())
    print(f'  皮肤总数: {total_skins}, .skel: {total_skel}, .atlas: {total_atlas}, .png: {total_png}')
    print()

    # 2. 构建 manifest
    manifest = build_manifest(groups)

    # 3. 复制
    print('整理文件...')
    stats = copy_groups(groups, out_dir, args.dry_run, args.clean)
    print(f'复制: {stats["copied"]}, 跳过(已存在): {stats["skipped"]}')
    print()

    # 4. 写入 manifest
    write_manifest(manifest, out_dir, args.dry_run)

    # 5. 输出样例
    print()
    print('资源样例（前10个）:')
    for i, (base, info) in enumerate(sorted(manifest['ships'].items())):
        if i >= 10:
            break
        skins = info['skins']
        skin_names = ', '.join(
            f'{k}({"有" if v["atlas"] else "缺"}atlas)'
            for k, v in skins.items()
        )
        print(f'  {base}: {len(skins)} 皮肤 [{skin_names}]')

    print()
    print('完成！将 organized/ 目录整体复制到手机:')
    print(f'  Download/BLYY/blhx_sd/')
    print(f'  （即 organized/ 下每个子目录对应一个舰娘，内含 <皮肤名>/ 子目录）')


if __name__ == '__main__':
    main()
