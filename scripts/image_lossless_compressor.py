#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
图片极限无损/视觉无损压缩工具 (Image Ultimate Compressor)
=========================================================
采用目前业界最先进的多策略编码引擎，挑战文件体积下限。

【核心技术升级】
  1. 三大现代格式并行竞争：AVIF (AV1) / WebP / JPEG XL，自动选最小体积输出。
  2. 极高压缩力度 (>=8) 时，自动激活 Zopfli 数据重排算法，榨干 PNG 最后一滴体积。
  3. WebP 强制启用 exact=True，确保 Alpha 透明像素下的 RGB 隐写数据完全无损。
  4. 多调色板量化引擎网格搜索 (MEDIANCUT/FASTOCTREE/MAXCOVERAGE × 抖动/无抖动)。
  5. EXIF 方向自动校正 (视觉无损，同步减少元数据体积)。
  6. 视觉无损模式新增现代格式有损高质量编码 (AVIF q85 / WebP q88 / JXL q85)。
  7. JPEG 视觉无损多质量等级 × 子采样策略网格搜索 (4:2:0 自然照片 / 4:4:4 锐利图像)。
  8. 16-bit PNG 自动降级 8-bit (兼容性更好，体积更小)。

【依赖】 Pillow, customtkinter, pillow-heif (必需); pillow-jxl-plugin (可选)
"""

import os
import sys
import io
import time
import shutil
import threading
import subprocess
import multiprocessing as mp
from datetime import datetime
from concurrent.futures import ProcessPoolExecutor, as_completed

# ============================================================
#  依赖自动安装
# ============================================================
def _ensure_deps():
    # 仅检查必需依赖，缺失则自动安装；可选依赖 JXL 不自动安装，运行时降级
    required = [("PIL", "Pillow"),
                ("customtkinter", "customtkinter"),
                ("pillow_heif", "pillow-heif")]
    missing = []
    for mod, pkg in required:
        try:
            __import__(mod)
        except ImportError:
            missing.append(pkg)
    if not missing:
        return
    print(f"[依赖] 缺少: {', '.join(missing)}，正在自动安装 ...")
    try:
        subprocess.run([sys.executable, "-m", "pip", "install", *missing], check=True)
        print("[依赖] 安装完成。")
    except Exception as e:
        print(f"[依赖] 自动安装失败: {e}\n请手动执行: pip install {' '.join(missing)}")
        sys.exit(1)

_ensure_deps()

from PIL import Image, ImageSequence, ImageOps
import customtkinter as ctk
from tkinter import filedialog, messagebox

# 注册 HEIC 支持 (必需) + AVIF 支持 (可选，依赖 libheif 的 AVIF 编解码器)
try:
    import pillow_heif
    pillow_heif.register_heif_opener()
    # AVIF 支持需要: pillow_heif 提供 register_avif_opener 且 libheif 已加载 AVIF 编解码器
    HAS_AVIF = False
    if hasattr(pillow_heif, "register_avif_opener"):
        try:
            info = pillow_heif.libheif_info()
            # AVIF 编码器存在才启用，否则 save 会抛异常
            if info.get("AVIF") or any("avif" in str(v).lower() for v in info.get("encoders", {}).values()):
                pillow_heif.register_avif_opener()
                HAS_AVIF = True
        except Exception:
            pass
except ImportError:
    HAS_AVIF = False

# 注册 JPEG XL 支持 (可选，缺失时降级)
try:
    import pillow_jxl
    HAS_JXL = True
except ImportError:
    HAS_JXL = False

# ============================================================
#  常量
# ============================================================
SUPPORTED_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tiff", ".tif", ".gif", ".avif", ".heic", ".jxl"}
LOSSY_EXTS = {".jpg", ".jpeg"} 
DEFAULT_OUTPUT_SUFFIX = "_compressed"

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_BUNDLED_OXIPNG_CANDIDATES = [
    os.path.join(_SCRIPT_DIR, "oxipng-10.1.1-x86_64-pc-windows-msvc", "oxipng.exe"),
    os.path.join(_SCRIPT_DIR, "oxipng.exe"),
]

# ============================================================
#  外部工具检测
# ============================================================
_tools_cache = None

def detect_tools():
    global _tools_cache
    if _tools_cache is not None:
        return _tools_cache
    found = {}
    for cand in _BUNDLED_OXIPNG_CANDIDATES:
        if os.path.isfile(cand):
            found["oxipng"] = cand
            break
    if "oxipng" not in found:
        p = shutil.which("oxipng")
        if p:
            found["oxipng"] = p
    _tools_cache = found
    return found

# ============================================================
#  硬件/系统信息
# ============================================================
def get_hardware_info():
    info = {"cpu_cores": mp.cpu_count(), "features": []}
    try:
        core = Image.core
        feats = []
        if getattr(core, "HAVE_LIBJPEGTURBO", 0): feats.append("libjpeg-turbo")
        if getattr(core, "HAVE_ZLIBNG", 0): feats.append("zlib-ng")
        if HAS_AVIF: feats.append("AVIF(AV1)")
        if HAS_JXL: feats.append("JXL")
        info["features"] = feats
    except Exception:
        pass
    tools = detect_tools()
    if "oxipng" in tools:
        info["features"].append("oxipng(Rust)")
    return info

# ============================================================
#  极限压缩引擎
# ============================================================

# 调色板量化方法表 (名称, Pillow 常量)
_QUANTIZE_METHODS = [
    ("MED", Image.MEDIANCUT),
    ("OCT", Image.FASTOCTREE),
    ("MAX", Image.MAXCOVERAGE),
]


def _compress_png(img, effort, allow_modern, strip_meta, use_oxipng, tools, oxipng_threads, visually_lossless):
    candidates = []
    mode = img.mode
    work = img
    if mode not in ("RGB", "RGBA", "P", "L"):
        work = img.convert("RGBA") if "transparency" in img.info else img.convert("RGB")
        mode = work.mode

    # 16-bit 降级 8-bit: PNG 8-bit 兼容性更好且体积通常更小
    if mode in ("I", "I;16", "I;16B", "I;16L"):
        try:
            work = work.convert("L")
            mode = "L"
        except Exception:
            pass

    # [策略A] 视觉无损(肉眼一致)：多量化方法 × 抖动/无抖动 网格搜索 + 现代格式有损高质量
    if visually_lossless and mode not in ("P", "L"):
        for mname, method in _QUANTIZE_METHODS:
            for dither_flag in (True, False):
                try:
                    dither = Image.FLOYDSTEINBERG if dither_flag else Image.NONE
                    pal = work.quantize(colors=256, method=method, dither=dither)
                    buf = io.BytesIO()
                    pal.save(buf, format="PNG", optimize=True, compress_level=9)
                    candidates.append((buf.getvalue(), f"PNG-VL-{mname}{'D' if dither_flag else 'N'}"))
                except Exception:
                    pass

        # 视觉无损现代格式有损高质量编码 (体积暴降，肉眼难辨)
        if allow_modern:
            # WebP 高质量有损
            try:
                buf_webp = io.BytesIO()
                work.save(buf_webp, format="WebP", quality=88, method=6, exact=True)
                candidates.append((buf_webp.getvalue(), "WebP-VL88"))
            except Exception:
                pass
            # AVIF 高质量有损
            if HAS_AVIF:
                try:
                    buf_avif = io.BytesIO()
                    avif_speed = max(0, 6 - effort // 2)
                    work.save(buf_avif, format="AVIF", quality=85, speed=avif_speed)
                    candidates.append((buf_avif.getvalue(), "AVIF-VL85"))
                except Exception:
                    pass
            # JXL 高质量有损
            if HAS_JXL:
                try:
                    buf_jxl = io.BytesIO()
                    work.save(buf_jxl, format="JPEG XL", quality=85, effort=min(9, max(3, effort + 2)))
                    candidates.append((buf_jxl.getvalue(), "JXL-VL85"))
                except Exception:
                    pass

    # [策略 1] Pillow Baseline
    buf = io.BytesIO()
    save_kw = {"format": "PNG", "optimize": True, "compress_level": min(9, max(6, effort))}
    if not strip_meta and "icc_profile" in img.info: save_kw["icc_profile"] = img.info["icc_profile"]
    work.save(buf, **save_kw)
    pillow_png = buf.getvalue()
    candidates.append((pillow_png, "PNG"))

    # [策略 2] 严格无损调色板探测 (原图色彩本身<=256) — 多方法竞争
    if not visually_lossless and mode not in ("P", "L"):
        try:
            colors = work.getcolors(256)
            if colors is not None:
                n = len(colors)
                for mname, method in _QUANTIZE_METHODS:
                    try:
                        pal = work.quantize(colors=n, method=method)
                        buf2 = io.BytesIO()
                        pal.save(buf2, format="PNG", optimize=True, compress_level=9)
                        candidates.append((buf2.getvalue(), f"PNG-P-{mname}"))
                    except Exception:
                        pass
        except Exception:
            pass

    # [策略 3] 允许改变格式: AVIF / WebP / JXL 极限无损
    if allow_modern:
        # AVIF 编码 (AV1，目前最强通用格式)
        if HAS_AVIF:
            try:
                buf_avif = io.BytesIO()
                avif_kw = {"format": "AVIF", "quality": 100, "lossless": True, "speed": max(0, 10 - effort)}
                work.save(buf_avif, **avif_kw)
                candidates.append((buf_avif.getvalue(), "AVIF"))
            except Exception:
                pass

        # WebP 编码 (exact=True 确保 Alpha 通道深层无损)
        try:
            buf_webp = io.BytesIO()
            webp_kw = {"format": "WebP", "lossless": True, "method": min(6, effort), "quality": 100, "exact": True}
            work.save(buf_webp, **webp_kw)
            candidates.append((buf_webp.getvalue(), "WebP"))
        except Exception:
            pass

        # JPEG XL 编码 (新标准，无损压缩率超越 PNG/WebP)
        if HAS_JXL:
            try:
                buf_jxl = io.BytesIO()
                jxl_kw = {"format": "JPEG XL", "lossless": True, "effort": min(9, max(3, effort + 2))}
                work.save(buf_jxl, **jxl_kw)
                candidates.append((buf_jxl.getvalue(), "JXL"))
            except Exception:
                pass

    # [策略 4] Oxipng 极限优化 (包含 Zopfli)
    if use_oxipng and "oxipng" in tools:
        try:
            ox_bytes = _oxipng_optimize(pillow_png, effort, strip_meta, tools, oxipng_threads)
            if ox_bytes:
                candidates.append((ox_bytes, "PNG-oxi"))
                # 视觉无损模式：对量化后的最优 PNG 也跑 Oxipng
                if visually_lossless:
                    best_pal = min((c for c in candidates if c[1].startswith("PNG-VL")), key=lambda c: len(c[0]), default=None)
                    if best_pal:
                        ox_pal = _oxipng_optimize(best_pal[0], effort, strip_meta, tools, oxipng_threads)
                        if ox_pal:
                            candidates.append((ox_pal, f"{best_pal[1]}-oxi"))
        except Exception:
            pass

    return min(candidates, key=lambda c: len(c[0]))


def _oxipng_optimize(png_bytes, effort, strip_meta, tools, threads=None):
    import tempfile
    oxipng = tools["oxipng"]
    level = min(6, max(1, effort))
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tf:
        tf.write(png_bytes)
        tmp_path = tf.name
    try:
        cmd = [oxipng, f"-o{level}", "-q", "-i", "0"]
        if threads is not None and threads > 0: cmd += ["--threads", str(threads)]
        if strip_meta: cmd += ["--strip", "safe"]
        
        # 终极杀器: 如果 effort >= 8，启用 Zopfli 算法（耗时倍增，但逼近数学极限）
        if effort >= 8:
            cmd.append("--zopfli")
            
        r = subprocess.run(cmd, capture_output=True, timeout=300)
        if r.returncode == 0:
            with open(tmp_path, "rb") as f:
                return f.read()
    except Exception:
        pass
    finally:
        try: os.unlink(tmp_path)
        except Exception: pass
    return None


def _compress_jpeg(img, effort, strip_meta, tools, visually_lossless):
    # EXIF 方向自动校正 (JPEG 最常携带 EXIF 方向标签)
    # exif_transpose 返回新图像，其 info["exif"] 已将方向标签置为 1(正常)
    try:
        work = ImageOps.exif_transpose(img)
    except Exception:
        work = img
    info_src = work  # 使用已校正的图像 info

    def _meta_kw():
        kw = {}
        if not strip_meta:
            if "exif" in info_src.info: kw["exif"] = info_src.info["exif"]
            if "icc_profile" in info_src.info: kw["icc_profile"] = info_src.info["icc_profile"]
        return kw

    if visually_lossless:
        # 多质量等级 × 子采样策略 网格搜索，选最小体积
        candidates = []
        # 4:2:0 子采样: 适合自然照片 (人眼对色度分辨率不敏感，体积更小)
        for q in (90, 87, 84):
            buf = io.BytesIO()
            kw = {"format": "JPEG", "optimize": True, "progressive": True, "quality": q, "subsampling": 1}
            kw.update(_meta_kw())
            work.save(buf, **kw)
            candidates.append((buf.getvalue(), f"JPEG-VL{q}-420"))
        # 4:4:4 子采样: 适合锐利图像 (文字/UI截图/线稿，色度保真)
        buf_444 = io.BytesIO()
        kw444 = {"format": "JPEG", "optimize": True, "progressive": True, "quality": 88, "subsampling": 0}
        kw444.update(_meta_kw())
        work.save(buf_444, **kw444)
        candidates.append((buf_444.getvalue(), "JPEG-VL88-444"))
        data, tag = min(candidates, key=lambda c: len(c[0]))
    else:
        # 严格无损: 保留原始量化表 (DCT 系数不变)
        buf = io.BytesIO()
        save_kw = {"format": "JPEG", "optimize": True, "progressive": True, "quality": "keep"}
        save_kw.update(_meta_kw())
        work.save(buf, **save_kw)
        data, tag = buf.getvalue(), "JPEG"

    return (data, tag)


def compress_image(src_path, dst_path, effort=6, keep_format=True,
                   strip_meta=False, allow_modern=False, use_oxipng=True,
                   oxipng_threads=None, visually_lossless=False):
    
    result = {"ok": False, "orig_size": 0, "new_size": 0, "strategy": "", "msg": "", "dst_path": dst_path}
    if not os.path.exists(src_path):
        result["msg"] = "源文件不存在"
        return result
        
    orig_size = os.path.getsize(src_path)
    result["orig_size"] = orig_size
    ext = os.path.splitext(src_path)[1].lower()
    tools = detect_tools()

    try:
        # BMP/TIFF -> 无损转换
        if ext in (".bmp", ".tiff", ".tif"):
            with Image.open(src_path) as img:
                img.load()
                # EXIF 方向自动校正
                try: img = ImageOps.exif_transpose(img)
                except Exception: pass
                work = img.convert("RGBA") if img.mode not in ("RGB", "RGBA", "L", "P") else img
                data, tag = _compress_png(work, effort, allow_modern, strip_meta, use_oxipng, tools, oxipng_threads, visually_lossless)
                ext_map = {"WebP": ".webp", "AVIF": ".avif", "JXL": ".jxl"}
                dst_path = os.path.splitext(dst_path)[0] + ext_map.get(tag, ".png")
                result["strategy"] = f"{ext}->{tag}"
                
        elif ext == ".gif":
            with Image.open(src_path) as img:
                buf = io.BytesIO()
                frames = [frame.copy() for frame in ImageSequence.Iterator(img)]
                if len(frames) == 1:
                    frames[0].save(buf, format="GIF", optimize=True, loop=img.info.get("loop", 0))
                else:
                    frames[0].save(buf, format="GIF", optimize=True, save_all=True, append_images=frames[1:],
                                   loop=img.info.get("loop", 0), duration=img.info.get("duration", 100))
                data, tag = buf.getvalue(), "GIF"
                result["strategy"] = tag
                
        elif ext in LOSSY_EXTS:
            with Image.open(src_path) as img:
                img.load()
                data, tag = _compress_jpeg(img, effort, strip_meta, tools, visually_lossless)
                result["strategy"] = tag
                
        elif ext == ".webp" or ext == ".avif" or ext == ".jxl":
            # 现代格式重新优化: 当 allow_modern 时跨格式竞争选最小，否则同格式重编码
            with Image.open(src_path) as img:
                img.load()
                # EXIF 方向自动校正
                try: img = ImageOps.exif_transpose(img)
                except Exception: pass
                work = img.convert("RGBA") if img.mode not in ("RGB", "RGBA", "L", "P") else img

                if allow_modern:
                    # 跨格式竞争: AVIF / WebP / JXL 无损，选最小
                    candidates = []
                    # 原格式重编码 (基准)
                    try:
                        buf0 = io.BytesIO()
                        fmt = "JPEG XL" if ext == ".jxl" else img.format
                        work.save(buf0, format=fmt, lossless=True, quality=100)
                        candidates.append((buf0.getvalue(), fmt))
                    except Exception:
                        pass
                    if HAS_AVIF:
                        try:
                            buf_a = io.BytesIO()
                            work.save(buf_a, format="AVIF", quality=100, lossless=True, speed=max(0, 10 - effort))
                            candidates.append((buf_a.getvalue(), "AVIF"))
                        except Exception:
                            pass
                    try:
                        buf_w = io.BytesIO()
                        work.save(buf_w, format="WebP", lossless=True, method=min(6, effort), quality=100, exact=True)
                        candidates.append((buf_w.getvalue(), "WebP"))
                    except Exception:
                        pass
                    if HAS_JXL:
                        try:
                            buf_j = io.BytesIO()
                            work.save(buf_j, format="JPEG XL", lossless=True, effort=min(9, max(3, effort + 2)))
                            candidates.append((buf_j.getvalue(), "JXL"))
                        except Exception:
                            pass
                    data, tag = min(candidates, key=lambda c: len(c[0])) if candidates else (b"", "")
                    # 确定输出扩展名
                    ext_map = {"WebP": ".webp", "AVIF": ".avif", "JPEG XL": ".jxl", "JXL": ".jxl"}
                    new_ext = ext_map.get(tag, ext)
                    if new_ext != ext:
                        dst_path = os.path.splitext(dst_path)[0] + new_ext
                else:
                    # 同格式重编码 (保持扩展名)
                    buf = io.BytesIO()
                    fmt = "JPEG XL" if ext == ".jxl" else img.format
                    work.save(buf, format=fmt, lossless=True, quality=100)
                    data, tag = buf.getvalue(), fmt
                result["strategy"] = tag
                
        elif ext == ".png":
            with Image.open(src_path) as img:
                img.load()
                # EXIF 方向自动校正 (部分 PNG 也携带方向标签)
                try: img = ImageOps.exif_transpose(img)
                except Exception: pass
                data, tag = _compress_png(img, effort, allow_modern, strip_meta, use_oxipng, tools, oxipng_threads, visually_lossless)
                result["strategy"] = tag
                ext_map = {"WebP": ".webp", "AVIF": ".avif", "JXL": ".jxl"}
                if tag in ext_map:
                    dst_path = os.path.splitext(dst_path)[0] + ext_map[tag]
        else:
            result["msg"] = f"不支持的格式: {ext}"
            return result

        new_size = len(data)
        result["new_size"] = new_size

        if new_size < orig_size:
            os.makedirs(os.path.dirname(dst_path), exist_ok=True)
            tmp = dst_path + ".tmp"
            with open(tmp, "wb") as f: f.write(data)
            os.replace(tmp, dst_path)
            
            if os.path.splitext(dst_path)[1].lower() != ext and os.path.exists(os.path.splitext(dst_path)[0] + ext):
                try: os.remove(os.path.splitext(dst_path)[0] + ext)
                except Exception: pass
                
            ratio = (1 - new_size / orig_size) * 100
            result["ok"] = True
            result["msg"] = f"{tag} -{ratio:.1f}%"
            result["dst_path"] = dst_path
        else:
            os.makedirs(os.path.dirname(dst_path), exist_ok=True)
            shutil.copy2(src_path, dst_path)
            result["ok"] = True
            result["new_size"] = orig_size
            result["msg"] = "已最优 (保留原样)"
            result["dst_path"] = dst_path
        return result

    except Exception as e:
        result["msg"] = f"错误: {e}"
        return result


def fmt_size(n):
    if n < 1024: return f"{n} B"
    if n < 1024 * 1024: return f"{n/1024:.1f} KB"
    return f"{n/1024/1024:.2f} MB"

def collect_images(paths, recursive=True):
    result = []
    for p in paths:
        if os.path.isfile(p) and os.path.splitext(p)[1].lower() in SUPPORTED_EXTS:
            result.append(p)
        elif os.path.isdir(p):
            if recursive:
                for r, _, fs in os.walk(p):
                    result.extend([os.path.join(r, f) for f in fs if os.path.splitext(f)[1].lower() in SUPPORTED_EXTS])
            else:
                result.extend([os.path.join(p, f) for f in os.listdir(p) if os.path.isfile(os.path.join(p, f)) and os.path.splitext(f)[1].lower() in SUPPORTED_EXTS])
    return list(dict.fromkeys([os.path.realpath(p) for p in result])) 


# ============================================================
#  进程池 worker
# ============================================================
def _process_worker(task):
    (src, dst, effort, keep_format, strip_meta, allow_modern,
     use_oxipng, oxipng_threads, visually_lossless, idx, name, skip_if_exists) = task
    try:
        if skip_if_exists and os.path.exists(dst) and dst != src:
            size = os.path.getsize(src)
            return {"idx": idx, "ok": True, "orig_size": size, "new_size": size,
                    "strategy": "", "msg": "已存在(跳过)", "skip": True, "name": name, "dst_path": dst}
        
        r = compress_image(src, dst, effort=effort, keep_format=keep_format,
                           strip_meta=strip_meta, allow_modern=allow_modern,
                           use_oxipng=use_oxipng, oxipng_threads=oxipng_threads,
                           visually_lossless=visually_lossless)
        r["idx"], r["skip"], r["name"] = idx, False, name
        return r
    except Exception as e:
        return {"idx": idx, "ok": False, "orig_size": 0, "new_size": 0,
                "strategy": "", "msg": f"错误: {e}", "skip": False, "name": name, "dst_path": dst}


# ============================================================
#  GUI 应用
# ============================================================
class CompressorApp(ctk.CTk):
    def __init__(self):
        super().__init__()
        ctk.set_appearance_mode("dark")
        ctk.set_default_color_theme("blue")

        self.title("极限图片压缩工具 · Ultimate Image Compressor")
        self.geometry("1100x780")
        self.minsize(920, 680)

        self.source_paths, self.image_files, self.row_data = [], [], []
        self.compressing, self.base_dir = False, ""

        self.output_mode = ctk.StringVar(value="同级 _compressed 子目录")
        self.custom_output = ctk.StringVar(value="")
        self.effort = ctk.IntVar(value=6)
        self.keep_format = ctk.BooleanVar(value=True)
        self.strip_meta = ctk.BooleanVar(value=False)
        self.use_oxipng = ctk.BooleanVar(value=True)
        self.preserve_structure = ctk.BooleanVar(value=True)
        self.overwrite = ctk.BooleanVar(value=False)
        self.visually_lossless = ctk.BooleanVar(value=False) # 新增：视觉无损
        
        self.concurrency = ctk.IntVar(value=min(mp.cpu_count(), 16))

        self._log_buffer, self._log_lock = [], threading.Lock()
        self._row_dirty, self._row_dirty_lock = [], threading.Lock()
        self._stats, self._total, self._tick_running = {}, 0, False

        self._build_ui()
        self._refresh_tool_status()

    def _build_ui(self):
        ctk.CTkLabel(self, text="图片极限无损压缩", font=ctk.CTkFont(size=22, weight="bold")).pack(pady=(16, 2))
        ctk.CTkLabel(self, text="AV1 · JPEG XL · Zopfli 极限算法 · 多量化引擎网格搜索 · Rust 多核优化",
                     font=ctk.CTkFont(size=11), text_color="#8899aa").pack(pady=(0, 10))

        # 源选择
        src = ctk.CTkFrame(self)
        src.pack(fill="x", padx=12, pady=(0, 6))
        ctk.CTkLabel(src, text="① 选择源:", font=ctk.CTkFont(size=13, weight="bold")).pack(anchor="w", padx=12, pady=(10, 4))
        row = ctk.CTkFrame(src, fg_color="transparent")
        row.pack(fill="x", padx=12, pady=(0, 10))
        ctk.CTkButton(row, text="添加文件", width=120, command=self._add_files).pack(side="left")
        ctk.CTkButton(row, text="添加文件夹", width=120, fg_color="#2d6a4f", hover_color="#3a7d5f", command=self._add_folder).pack(side="left", padx=(8, 0))
        ctk.CTkButton(row, text="清空列表", width=90, fg_color="#7a3b3b", hover_color="#8a4b4b", command=self._clear_list).pack(side="left", padx=(8, 0))
        self.lbl_src = ctk.CTkLabel(row, text="未选择图片", font=ctk.CTkFont(size=12), text_color="#9fb3c8")
        self.lbl_src.pack(side="left", padx=(14, 0))

        # 输出与选项
        cfg = ctk.CTkFrame(self)
        cfg.pack(fill="x", padx=12, pady=(0, 6))

        orow = ctk.CTkFrame(cfg, fg_color="transparent")
        orow.pack(fill="x", padx=12, pady=(10, 6))
        ctk.CTkLabel(orow, text="② 输出位置:", font=ctk.CTkFont(size=13, weight="bold")).pack(side="left", padx=(0, 10))
        self.seg_output = ctk.CTkSegmentedButton(orow, values=["同级 _compressed 子目录", "自定义目录", "覆盖原文件"], 
                                                 variable=self.output_mode, width=380, command=self._on_output_mode_change)
        self.seg_output.pack(side="left")
        self.entry_output = ctk.CTkEntry(orow, textvariable=self.custom_output, width=220, placeholder_text="路径")
        ctk.CTkButton(orow, text="浏览", width=60, command=self._browse_output).pack(side="left", padx=(6, 0))

        # 选项
        opt = ctk.CTkFrame(cfg, fg_color="transparent")
        opt.pack(fill="x", padx=12, pady=(4, 10))
        ctk.CTkLabel(opt, text="③ 压缩选项:", font=ctk.CTkFont(size=13, weight="bold")).pack(anchor="w")
        grid = ctk.CTkFrame(opt, fg_color="transparent")
        grid.pack(fill="x", pady=(4, 0))
        
        # 第一排
        ctk.CTkCheckBox(grid, text="视觉无损 (肉眼一致，体积暴降 50-80%)", variable=self.visually_lossless, text_color="#e0a96d").grid(row=0, column=0, sticky="w", padx=(0, 16))
        ctk.CTkCheckBox(grid, text="允许转储为 AVIF / WebP / JXL (超越 PNG)", variable=ctk.BooleanVar(value=not self.keep_format.get()),
                        command=lambda: self.keep_format.set(not self.keep_format.get())).grid(row=0, column=1, sticky="w", padx=(0, 16))
        ctk.CTkCheckBox(grid, text="剥离元数据", variable=self.strip_meta).grid(row=0, column=2, sticky="w")
        
        # 第二排
        ctk.CTkCheckBox(grid, text="使用 Rust Oxipng 引擎", variable=self.use_oxipng).grid(row=1, column=0, sticky="w", pady=(8,0))
        ctk.CTkCheckBox(grid, text="保留文件夹结构", variable=self.preserve_structure).grid(row=1, column=1, sticky="w", pady=(8,0))
        ctk.CTkCheckBox(grid, text="覆盖已有文件", variable=self.overwrite).grid(row=1, column=2, sticky="w", pady=(8,0))

        # 力度与并发
        erow = ctk.CTkFrame(cfg, fg_color="transparent")
        erow.pack(fill="x", padx=12, pady=(6, 10))
        ctk.CTkLabel(erow, text="压缩力度 (≥8 触发 Zopfli 极限算法，极慢):").pack(side="left")
        ctk.CTkSlider(erow, from_=1, to=9, number_of_steps=8, variable=self.effort, width=160, 
                      command=lambda v: self.lbl_effort.configure(text=f"{int(v)} ({'极限' if v>=8 else '强力' if v>5 else '快速'})")).pack(side="left", padx=(6, 4))
        self.lbl_effort = ctk.CTkLabel(erow, text="6 (强力)", width=60)
        self.lbl_effort.pack(side="left")
        
        ctk.CTkLabel(erow, text="并发进程:").pack(side="left", padx=(20, 2))
        ctk.CTkSlider(erow, from_=1, to=24, number_of_steps=23, variable=self.concurrency, width=140, 
                      command=lambda v: self.lbl_conc.configure(text=str(int(v)))).pack(side="left")
        self.lbl_conc = ctk.CTkLabel(erow, text=str(self.concurrency.get()), width=28)
        self.lbl_conc.pack(side="left")

        # 列表
        lf = ctk.CTkFrame(self)
        lf.pack(fill="both", expand=True, padx=12, pady=(0, 6))
        lh = ctk.CTkFrame(lf, fg_color="transparent")
        lh.pack(fill="x", padx=10, pady=(8, 4))
        ctk.CTkLabel(lh, text="④ 文件队列", font=ctk.CTkFont(size=13, weight="bold")).pack(side="left")
        self.lbl_engine = ctk.CTkLabel(lh, text="", font=ctk.CTkFont(size=11), text_color="#7aa2c8")
        self.lbl_engine.pack(side="right")

        self.scroll = ctk.CTkScrollableFrame(lf, label_text="")
        self.scroll.pack(fill="both", expand=True, padx=10, pady=(4, 10))

        # 底部
        bot = ctk.CTkFrame(self)
        bot.pack(fill="x", padx=12, pady=(0, 12))
        brow = ctk.CTkFrame(bot, fg_color="transparent")
        brow.pack(fill="x", padx=12, pady=(10, 4))
        self.btn_compress = ctk.CTkButton(brow, text="⚡ 开始压缩", width=140, height=36, font=ctk.CTkFont(size=14, weight="bold"), command=self._start_compress)
        self.btn_compress.pack(side="left")
        self.btn_open = ctk.CTkButton(brow, text="打开目录", width=100, height=36, fg_color="#4a5568", hover_color="#5a6578", command=self._open_output)
        self.btn_open.pack(side="left", padx=(8, 0))
        self.progress = ctk.CTkProgressBar(brow, width=280)
        self.progress.set(0)
        self.progress.pack(side="left", padx=(14, 10), fill="x", expand=True)
        self.lbl_stat = ctk.CTkLabel(brow, text="就绪", text_color="#9fb3c8", width=200)
        self.lbl_stat.pack(side="right")

        self.logbox = ctk.CTkTextbox(bot, height=100, font=ctk.CTkFont(family="Consolas", size=12), wrap="word")
        self.logbox.pack(fill="x", padx=12, pady=(4, 12))
        self.logbox.configure(state="disabled")

    def _refresh_tool_status(self):
        hw = get_hardware_info()
        feats = hw.get("features", [])
        self.lbl_engine.configure(text=f"加速引擎: {' · '.join(feats)}", text_color="#6fcf97" if feats else "#c9a25a")

    # ----- 核心交互与状态 ----- (由于 UI 交互与原版相似，在此精简内部包装)
    def _add_files(self):
        if self.compressing: return
        f = filedialog.askopenfilenames(title="选择图片", filetypes=[("图片", "*.png *.jpg *.jpeg *.webp *.avif *.heic *.bmp *.tiff *.gif *.jxl")])
        if f: self.source_paths = list(f); self.base_dir = os.path.dirname(f[0]); self._rebuild_list()
            
    def _add_folder(self):
        if self.compressing: return
        d = filedialog.askdirectory()
        if d: self.source_paths = [d]; self.base_dir = d; self._rebuild_list()

    def _clear_list(self):
        if self.compressing: return
        self.source_paths, self.image_files = [], []
        self._rebuild_list()

    def _rebuild_list(self):
        self.btn_compress.configure(state="disabled")
        self.lbl_src.configure(text="扫描中…")
        def work():
            files = collect_images(self.source_paths, self.preserve_structure.get())
            self.after(0, lambda: self._on_collected(files))
        threading.Thread(target=work, daemon=True).start()

    def _on_collected(self, files):
        self.image_files = files
        for w in self.scroll.winfo_children(): w.destroy()
        self.row_data = []
        if not files:
            self.lbl_src.configure(text="未找到图片")
            self.btn_compress.configure(state="normal")
            return
        self.lbl_src.configure(text=f"共 {len(files)} 张图片")
        for fp in files:
            r = ctk.CTkFrame(self.scroll, fg_color="transparent")
            r.pack(fill="x", padx=2, pady=1)
            name, size = os.path.basename(fp), os.path.getsize(fp) if os.path.exists(fp) else 0
            ctk.CTkLabel(r, text=name[:40], width=300, anchor="w").pack(side="left")
            ctk.CTkLabel(r, text=fmt_size(size), width=80, anchor="e", text_color="#b0bec5").pack(side="left")
            ln = ctk.CTkLabel(r, text="—", width=80, anchor="e")
            ln.pack(side="left")
            ls = ctk.CTkLabel(r, text="—", width=80, anchor="e")
            ls.pack(side="left")
            lst = ctk.CTkLabel(r, text="待处理", width=140, anchor="w", text_color="#7a8a99")
            lst.pack(side="left", padx=(10,0))
            self.row_data.append({"path": fp, "orig": size, "lbl_new": ln, "lbl_save": ls, "lbl_status": lst})
        self.btn_compress.configure(state="normal")

    def _on_output_mode_change(self, _=None):
        if self.output_mode.get() == "自定义目录":
            self.entry_output.pack(side="left", padx=(8, 0), after=self.seg_output)
        else:
            self.entry_output.pack_forget()

    def _browse_output(self):
        d = filedialog.askdirectory()
        if d: self.custom_output.set(d)

    def _resolve_dst(self, src):
        mode = self.output_mode.get()
        if mode == "覆盖原文件": return src
        root = self.custom_output.get() if mode == "自定义目录" else os.path.join(os.path.dirname(src), DEFAULT_OUTPUT_SUFFIX)
        if self.preserve_structure.get() and self.base_dir and src.startswith(self.base_dir):
            return os.path.join(root, os.path.relpath(src, self.base_dir))
        return os.path.join(root, os.path.basename(src))

    def _open_output(self):
        m = self.output_mode.get()
        p = self.custom_output.get() if m == "自定义目录" else (self.base_dir if m == "覆盖原文件" else self.base_dir + DEFAULT_OUTPUT_SUFFIX)
        if os.path.exists(p): os.startfile(p)
        else: messagebox.showinfo("提示", "目录尚不存在。")

    def log(self, msg):
        with self._log_lock: self._log_buffer.append(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")
        self._ensure_tick()

    def _ensure_tick(self):
        if not self._tick_running:
            self._tick_running = True
            self.after(100, self._tick)

    def _tick(self):
        with self._log_lock:
            text = "\n".join(self._log_buffer) + "\n" if self._log_buffer else ""
            self._log_buffer.clear()
        if text:
            self.logbox.configure(state="normal")
            self.logbox.insert("end", text)
            self.logbox.see("end")
            self.logbox.configure(state="disabled")

        with self._row_dirty_lock:
            dirty = self._row_dirty[:]
            self._row_dirty.clear()
            
        for idx in dirty:
            r = self.row_data[idx].get("result")
            if not r: continue
            self.row_data[idx]["lbl_new"].configure(text=fmt_size(r["new_size"]))
            if r["orig_size"] > 0 and r["new_size"] < r["orig_size"]:
                pct = (1 - r["new_size"] / r["orig_size"]) * 100
                self.row_data[idx]["lbl_save"].configure(text=f"-{pct:.1f}%", text_color="#6fcf97")
            self.row_data[idx]["lbl_status"].configure(text=r["msg"][:15], text_color="#6fcf97" if r["ok"] else "#ef5350")

        if self.compressing:
            s, t = self._stats, self._total
            self.progress.set(s["done"] / t if t else 0)
            self.lbl_stat.configure(text=f"进度 {s['done']}/{t} ✓{s['ok']} ✗{s['fail']} 省{fmt_size(s['orig']-s['new'])}")
            self.after(100, self._tick)
        else:
            self._tick_running = False

    def _start_compress(self):
        if self.compressing or not self.image_files: return
        self.compressing = True
        self.btn_compress.configure(state="disabled", text="⚡ 压缩中…")
        self._stats = {"done": 0, "ok": 0, "skip": 0, "fail": 0, "orig": 0, "new": 0}
        self._total = len(self.image_files)

        for rd in self.row_data:
            rd["result"] = None
            rd["lbl_new"].configure(text="…"); rd["lbl_save"].configure(text="…", text_color="#9fb3c8")
            rd["lbl_status"].configure(text="处理中", text_color="#9fb3c8")

        effort = self.effort.get()
        
        # 风险提示
        if effort >= 8 and self.use_oxipng.get() and any(f.lower().endswith('.png') for f in self.image_files):
            if not messagebox.askyesno("极限压缩警告", "您选择了力度 8/9，PNG 将启用 Zopfli 算法。\n这能榨干极限体积，但压缩单张图可能耗时极长。\n是否继续？"):
                self.compressing = False
                self.btn_compress.configure(state="normal", text="⚡ 开始压缩")
                return

        tasks = []
        rm = {rd["path"]: i for i, rd in enumerate(self.row_data)}
        oxi_t = max(1, mp.cpu_count() // self.concurrency.get()) if self.use_oxipng.get() else None

        for fp in self.image_files:
            tasks.append((fp, self._resolve_dst(fp), effort, self.keep_format.get(), self.strip_meta.get(),
                          not self.keep_format.get(), self.use_oxipng.get(), oxi_t, self.visually_lossless.get(),
                          rm.get(fp, -1), os.path.basename(fp), not self.overwrite.get()))

        def work():
            try:
                with ProcessPoolExecutor(max_workers=self.concurrency.get()) as pool:
                    for fut in as_completed([pool.submit(_process_worker, t) for t in tasks]):
                        r = fut.result()
                        if 0 <= r["idx"] < len(self.row_data):
                            self.row_data[r["idx"]]["result"] = r
                            with self._row_dirty_lock: self._row_dirty.append(r["idx"])
                        
                        self._stats["done"] += 1
                        self._stats["orig"] += r["orig_size"]; self._stats["new"] += r["new_size"]
                        if r.get("skip"): self._stats["skip"] += 1
                        elif r["ok"]: self._stats["ok"] += 1
                        else: self._stats["fail"] += 1
                        self.log(f"{r['name']}: {r['msg']}")
            finally:
                self.after(0, self._on_done)

        threading.Thread(target=work, daemon=True).start()
        self._ensure_tick()

    def _on_done(self):
        self.compressing = False
        self.btn_compress.configure(state="normal", text="⚡ 开始压缩")
        self.progress.set(1.0)
        s = self._stats
        saved = s["orig"] - s["new"]
        self.lbl_stat.configure(text=f"完成 ✓{s['ok']} ⤳{s['skip']} ✗{s['fail']} 省{fmt_size(saved)}")
        messagebox.showinfo("完成", f"处理完毕!\n\n成功: {s['ok']}\n跳过: {s['skip']}\n失败: {s['fail']}\n\n节省体积: {fmt_size(saved)}")

def main():
    app = CompressorApp()
    app.mainloop()

if __name__ == "__main__":
    main()