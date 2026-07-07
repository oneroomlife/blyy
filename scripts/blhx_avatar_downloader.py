#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
碧蓝航线舰娘头像批量下载工具 (BLHX Avatar Downloader)
======================================================
从 wiki.biligame.com/blhx/舰船图鉴 批量下载舰娘头像图片。

【命名约定 —— 与 App 舰娘档案本地加载对齐】
  文件名: {舰名 sanitized}.{原扩展名}
    例: 标枪.png , 特装型布里MKIII.jpg , Z23.png
  舰名取自 WIKI 图鉴列表 .jntj-4 a 文本(取首个空格前部分),
  与 App 中 ShipRepository 提取的 Ship.name 完全一致,
  便于后期 App 通过 sanitize(shipName)+"."+ext 直接本地映射加载。

  同时在输出根目录生成 manifest.json, 记录 舰名 -> 文件名 的映射,
  供 App 直接读取以定位本地头像文件。

【主要功能】
  - 精美暗色图形界面 (customtkinter)
  - 自定义输出路径
  - 每次下载自动新建子文件夹 (时间戳 / 自定义名 / 不分类 三种模式)
  - 已下载图片跳过 (基于输出根目录全局 manifest, 跨子文件夹生效)
  - 阵营 / 舰种 / 稀有度 三维筛选 + 关键字搜索
  - 多线程并发下载 + 失败自动重试
  - 实时进度条 / 统计 / 日志
  - 可选同时下载稀有度边框 (命名: {舰名}_border.png)

【依赖】 requests, beautifulsoup4, customtkinter
  脚本启动时自动检测并安装缺失依赖。
"""

import os
import sys
import json
import re
import time
import threading
import subprocess
from datetime import datetime
from urllib.parse import urlparse

# ============================================================
#  依赖自动安装
# ============================================================
def _ensure_deps():
    missing = []
    for mod, pkg in [("requests", "requests"),
                     ("bs4", "beautifulsoup4"),
                     ("customtkinter", "customtkinter")]:
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

import requests
from requests.adapters import HTTPAdapter
from bs4 import BeautifulSoup
import customtkinter as ctk
from tkinter import filedialog, messagebox
from concurrent.futures import ThreadPoolExecutor, as_completed

# lxml 比 html.parser 快约 10 倍 (1.7MB 页面), 优先使用
try:
    import lxml  # noqa: F401
    _PARSER = "lxml"
except ImportError:
    _PARSER = "html.parser"

# ============================================================
#  常量
# ============================================================
SHIP_LIST_URL = "https://wiki.biligame.com/blhx/%E8%88%B0%E8%88%B9%E5%9B%BE%E9%89%B4"
WIKI_REFERER = "https://wiki.biligame.com/"
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
MANIFEST_NAME = "manifest.json"
DEFAULT_OUTPUT = os.path.join(os.path.expanduser("~"), "Downloads", "BLYY_Avatars")

# 稀有度 -> 标签颜色
RARITY_COLORS = {
    "普通": "#9e9e9e", "稀有": "#42a5f5", "精锐": "#ab47bc",
    "超稀有": "#ffb300", "海上传奇": "#ff5252", "决战方案": "#e040fb",
    "最高方案": "#ff80ab", "普通META": "#9e9e9e",
    "超稀有META": "#ff5252", "精锐META": "#ab47bc", "稀有META": "#42a5f5",
}
RARITY_ORDER = ["普通", "稀有", "精锐", "超稀有", "海上传奇", "决战方案",
                "最高方案", "超稀有META", "精锐META", "稀有META", "普通META"]


# ============================================================
#  HTTP Session (连接池复用, 同主机 keep-alive 大幅提速)
# ============================================================
_http_session = None


def get_http_session(pool_size=16):
    """全局 requests.Session, 带 HTTPAdapter 连接池.
    所有图片都来自 patchwiki.biligame.com 同一主机,
    连接池复用 TCP/TLS 连接可避免每张图重新握手, 吞吐量提升数倍."""
    global _http_session
    if _http_session is None:
        s = requests.Session()
        adapter = HTTPAdapter(
            pool_connections=max(pool_size, 4),
            pool_maxsize=max(pool_size, 4),
            max_retries=0,            # 手动重试, 不走 urllib3 内置重试
            pool_block=False,
        )
        s.mount("https://", adapter)
        s.mount("http://", adapter)
        s.headers.update({
            "User-Agent": USER_AGENT,
            "Referer": WIKI_REFERER,
            "Accept-Encoding": "gzip",
        })
        _http_session = s
    return _http_session

# ============================================================
#  数据模型
# ============================================================
class Ship:
    __slots__ = ("name", "link", "avatar_url", "border_url",
                 "position", "ship_type", "rarity", "faction", "extra")

    def __init__(self, name, link, avatar_url, border_url,
                 position="", ship_type="", rarity="", faction="", extra=""):
        self.name = name
        self.link = link
        self.avatar_url = avatar_url
        self.border_url = border_url
        self.position = position
        self.ship_type = ship_type
        self.rarity = rarity
        self.faction = faction
        self.extra = extra


# ============================================================
#  工具函数  (format_url 与 App 中 ShipRepository.formatUrl 完全一致)
# ============================================================
def format_url(url: str) -> str:
    """规范化 WIKI 图片 URL: 补全协议 + 剥离 /thumb/ 缩略图段, 还原原图."""
    if not url:
        return ""
    if url.startswith("//"):
        url = "https:" + url
    elif url.startswith("/"):
        url = "https://wiki.biligame.com" + url
    if "/thumb/" in url:
        ti = url.index("/thumb/")
        ls = url.rfind("/")
        if ls > ti + 7:
            url = url[:ti] + "/" + url[ti + 7:ls]
    return url


def sanitize_filename(name: str) -> str:
    """文件名安全化: 剔除 Windows 非法字符, 保留中文/字母/数字/特殊字符."""
    s = re.sub(r'[\\/:*?"<>|]', '_', name)
    s = s.strip('. ')
    return s if s else "unknown"


def get_extension(url: str) -> str:
    """从 URL 推断图片扩展名, 默认 png."""
    path = urlparse(url).path
    base = os.path.basename(path)
    if '.' in base:
        ext = base.rsplit('.', 1)[-1].lower()
        if ext in ('png', 'jpg', 'jpeg', 'gif', 'webp'):
            return 'png' if ext == 'jpeg' else ext
    return 'png'


# ============================================================
#  抓取舰娘列表
# ============================================================
def fetch_ship_list(progress_cb=None):
    """抓取 舰船图鉴 页面, 返回去重后的 Ship 列表."""
    if progress_cb:
        progress_cb("正在获取图鉴页面 ...")
    resp = get_http_session().get(SHIP_LIST_URL, timeout=25)
    resp.raise_for_status()
    resp.encoding = resp.apparent_encoding or 'utf-8'
    soup = BeautifulSoup(resp.text, _PARSER)

    ships = []
    seen = set()
    for el in soup.select(".jntj-1.divsort"):
        name_el = el.select_one(".jntj-4 a")
        if not name_el:
            continue
        # Jsoup 的 .text() 会把 <br> 当作空白分隔 (如 "新泽西<br>花园" -> "新泽西 花域"),
        # 而 BeautifulSoup 的 get_text() 会拼成 "新泽西花园". 用 separator 复刻 Jsoup 行为,
        # 再取首个空白分隔 token, 与 App 中 ShipRepository 取得的 Ship.name 完全一致.
        raw = name_el.get_text(separator=" ", strip=True)
        tokens = raw.split()
        name = tokens[0] if tokens else ""
        if not name or name in seen:
            continue

        link_el = el.select_one(".jntj-3 a")
        avatar_el = el.select_one(".jntj-2 img")
        border_el = el.select_one(".jntj-3 img")

        avatar = ""
        if avatar_el:
            avatar = avatar_el.get("src") or avatar_el.get("data-src") or ""
        avatar = format_url(avatar)
        if not avatar:
            continue
        border = format_url(border_el.get("src", "") if border_el else "")
        link = link_el.get("href", "") if link_el else ""

        # data-param1="前排先锋,,驱逐"  -> position, ship_type
        p1 = el.get("data-param1", "")
        parts = [p for p in p1.split(",") if p]
        position = parts[0] if len(parts) >= 1 else ""
        ship_type = parts[1] if len(parts) >= 2 else ""
        rarity = el.get("data-param2", "")
        faction = el.get("data-param3", "")
        extra = el.get("data-param4", "").strip(",")

        seen.add(name)
        ships.append(Ship(name, link, avatar, border,
                          position, ship_type, rarity, faction, extra))

    if progress_cb:
        progress_cb(f"获取到 {len(ships)} 艘舰娘")
    return ships


# ============================================================
#  manifest 管理  (输出根目录全局记录, 跨子文件夹去重)
# ============================================================
def load_manifest(path):
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict) and "ships" in data:
                    return data
        except Exception:
            pass
    return {"version": 1, "ships": {}}


def save_manifest(path, manifest):
    try:
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
    except Exception as e:
        print(f"[manifest] 保存失败: {e}")


# ============================================================
#  GUI 应用
# ============================================================
class AvatarDownloaderApp(ctk.CTk):

    def __init__(self):
        super().__init__()
        ctk.set_appearance_mode("dark")
        ctk.set_default_color_theme("blue")

        self.title("碧蓝航线 · 舰娘头像批量下载工具")
        self.geometry("1120x780")
        self.minsize(960, 680)

        # 状态
        self.ships = []                 # 全部舰娘
        self.ship_rows = []             # [(ship, row_frame, var), ...]
        self.download_lock = threading.Lock()
        self.downloading = False
        self.fetching = False

        # 节流式 UI 更新: 日志缓冲 + 统计快照, 由 _tick() 定时刷新到主线程.
        # 避免高并发下载时每张图都 after(0) 导致 Tk 事件队列积压卡顿.
        self._log_buffer = []
        self._log_lock = threading.Lock()
        self._stats = {"success": 0, "failed": 0, "skipped": 0, "done": 0, "bytes": 0}
        self._total = 0
        self._tick_running = False
        self.output_dir = ctk.StringVar(value=DEFAULT_OUTPUT)
        self.subfolder_mode = ctk.StringVar(value="时间戳")
        self.custom_subfolder = ctk.StringVar(value="")
        self.concurrency = ctk.IntVar(value=12)
        self.skip_existing = ctk.BooleanVar(value=True)
        self.download_border = ctk.BooleanVar(value=False)
        self.search_text = ctk.StringVar(value="")
        self.filter_faction = ctk.StringVar(value="全部")
        self.filter_type = ctk.StringVar(value="全部")
        self.filter_rarity = ctk.StringVar(value="全部")

        self._build_ui()
        # 启动后自动抓取列表
        self.after(300, self._refresh_list_threaded)

    # ---------------- UI 构建 ----------------
    def _build_ui(self):
        pad = {"padx": 10, "pady": (6, 6)}

        # 顶部标题
        title = ctk.CTkLabel(self, text="碧蓝航线 · 舰娘头像批量下载工具",
                             font=ctk.CTkFont(size=20, weight="bold"))
        title.pack(pady=(14, 4))
        ctk.CTkLabel(self,
                     text="数据来源 wiki.biligame.com/blhx/舰船图鉴  ·  文件名: {舰名}.{扩展名}",
                     font=ctk.CTkFont(size=11), text_color="#8899aa").pack(pady=(0, 8))

        # ---- 设置面板 ----
        cfg = ctk.CTkFrame(self)
        cfg.pack(fill="x", **pad)

        # 输出路径
        row1 = ctk.CTkFrame(cfg, fg_color="transparent")
        row1.pack(fill="x", padx=12, pady=(10, 4))
        ctk.CTkLabel(row1, text="输出目录:", width=70,
                     font=ctk.CTkFont(size=13)).pack(side="left")
        self.entry_output = ctk.CTkEntry(row1, textvariable=self.output_dir)
        self.entry_output.pack(side="left", fill="x", expand=True, padx=(4, 6))
        ctk.CTkButton(row1, text="浏览…", width=80, command=self._browse_output
                      ).pack(side="left")

        # 子文件夹模式 + 并发 + 选项
        row2 = ctk.CTkFrame(cfg, fg_color="transparent")
        row2.pack(fill="x", padx=12, pady=4)
        ctk.CTkLabel(row2, text="子文件夹:", width=70,
                     font=ctk.CTkFont(size=13)).pack(side="left")
        self.seg_subfolder = ctk.CTkSegmentedButton(
            row2, values=["时间戳", "自定义", "不分类"],
            variable=self.subfolder_mode, width=260,
            command=self._on_subfolder_mode_change)
        self.seg_subfolder.pack(side="left", padx=(4, 10))
        self.entry_subfolder = ctk.CTkEntry(
            row2, textvariable=self.custom_subfolder, width=160,
            placeholder_text="自定义子文件夹名")
        self.entry_subfolder.pack(side="left", padx=(0, 10), after=self.seg_subfolder)
        self.entry_subfolder.pack_forget()  # 默认时间戳, 隐藏自定义输入

        ctk.CTkLabel(row2, text="并发:", font=ctk.CTkFont(size=13)
                     ).pack(side="left", padx=(10, 2))
        ctk.CTkSlider(row2, from_=1, to=30, number_of_steps=29,
                      variable=self.concurrency, width=140,
                      command=self._on_concurrency_change).pack(side="left")
        self.lbl_concurrency = ctk.CTkLabel(row2, text="12", width=28,
                                            font=ctk.CTkFont(size=13))
        self.lbl_concurrency.pack(side="left")

        row3 = ctk.CTkFrame(cfg, fg_color="transparent")
        row3.pack(fill="x", padx=12, pady=(4, 10))
        ctk.CTkCheckBox(row3, text="跳过已下载 (跨子文件夹全局去重)",
                        variable=self.skip_existing,
                        font=ctk.CTkFont(size=13)).pack(side="left")
        ctk.CTkCheckBox(row3, text="同时下载稀有度边框 ({舰名}_border.png)",
                        variable=self.download_border,
                        font=ctk.CTkFont(size=13)).pack(side="left", padx=(20, 0))

        # ---- 筛选 + 舰娘列表 ----
        list_frame = ctk.CTkFrame(self)
        list_frame.pack(fill="both", expand=True, **pad)

        bar = ctk.CTkFrame(list_frame, fg_color="transparent")
        bar.pack(fill="x", padx=10, pady=(8, 4))
        ctk.CTkButton(bar, text="刷新列表", width=90,
                      command=self._refresh_list_threaded).pack(side="left")
        ctk.CTkLabel(bar, text="搜索:", font=ctk.CTkFont(size=13)
                     ).pack(side="left", padx=(12, 2))
        self.entry_search = ctk.CTkEntry(bar, textvariable=self.search_text,
                                         width=180, placeholder_text="舰名关键字")
        self.entry_search.pack(side="left")
        self.entry_search.bind("<KeyRelease>", lambda e: self._apply_filter())

        ctk.CTkLabel(bar, text="阵营:", font=ctk.CTkFont(size=13)
                     ).pack(side="left", padx=(12, 2))
        self.om_faction = ctk.CTkOptionMenu(bar, variable=self.filter_faction,
                                            values=["全部"], width=110,
                                            command=lambda v: self._apply_filter())
        self.om_faction.pack(side="left")
        ctk.CTkLabel(bar, text="舰种:", font=ctk.CTkFont(size=13)
                     ).pack(side="left", padx=(8, 2))
        self.om_type = ctk.CTkOptionMenu(bar, variable=self.filter_type,
                                         values=["全部"], width=110,
                                         command=lambda v: self._apply_filter())
        self.om_type.pack(side="left")
        ctk.CTkLabel(bar, text="稀有度:", font=ctk.CTkFont(size=13)
                     ).pack(side="left", padx=(8, 2))
        self.om_rarity = ctk.CTkOptionMenu(bar, variable=self.filter_rarity,
                                           values=["全部"], width=120,
                                           command=lambda v: self._apply_filter())
        self.om_rarity.pack(side="left")

        # 选择操作
        sel = ctk.CTkFrame(list_frame, fg_color="transparent")
        sel.pack(fill="x", padx=10, pady=2)
        ctk.CTkButton(sel, text="全选", width=70, fg_color="#2d6a4f",
                      command=self._select_all).pack(side="left")
        ctk.CTkButton(sel, text="全不选", width=70, fg_color="#7a3b3b",
                      command=self._select_none).pack(side="left", padx=(6, 0))
        ctk.CTkButton(sel, text="反选", width=70,
                      command=self._select_invert).pack(side="left", padx=(6, 0))
        self.lbl_count = ctk.CTkLabel(sel, text="舰娘: 0  |  已选: 0",
                                      font=ctk.CTkFont(size=13),
                                      text_color="#9fb3c8")
        self.lbl_count.pack(side="right")

        # 可滚动舰娘列表
        self.scroll = ctk.CTkScrollableFrame(list_frame, label_text="")
        self.scroll.pack(fill="both", expand=True, padx=10, pady=(4, 10))
        self.scroll.bind("<Configure>", lambda e: self._relayout_grid())

        # ---- 底部进度 + 日志 ----
        bottom = ctk.CTkFrame(self)
        bottom.pack(fill="x", **pad)

        prow = ctk.CTkFrame(bottom, fg_color="transparent")
        prow.pack(fill="x", padx=12, pady=(10, 4))
        self.btn_download = ctk.CTkButton(
            prow, text="开始下载", width=130, height=34,
            font=ctk.CTkFont(size=14, weight="bold"),
            command=self._start_download)
        self.btn_download.pack(side="left")
        self.btn_open = ctk.CTkButton(
            prow, text="打开输出目录", width=120, height=34,
            fg_color="#4a5568", hover_color="#5a6578",
            command=self._open_output)
        self.btn_open.pack(side="left", padx=(8, 0))
        self.progress = ctk.CTkProgressBar(prow, width=300)
        self.progress.set(0)
        self.progress.pack(side="left", padx=(14, 10), fill="x", expand=True)
        self.lbl_stat = ctk.CTkLabel(prow, text="就绪",
                                     font=ctk.CTkFont(size=12),
                                     text_color="#9fb3c8", width=220)
        self.lbl_stat.pack(side="right")

        self.logbox = ctk.CTkTextbox(bottom, height=130,
                                     font=ctk.CTkFont(family="Consolas",
                                                      size=12),
                                     wrap="word")
        self.logbox.pack(fill="x", padx=12, pady=(4, 12))
        self.logbox.configure(state="disabled")

    # ---------------- 交互回调 ----------------
    def _on_subfolder_mode_change(self, _=None):
        if self.subfolder_mode.get() == "自定义":
            self.entry_subfolder.pack(side="left", padx=(0, 10),
                                      after=self.seg_subfolder)
        else:
            self.entry_subfolder.pack_forget()

    def _on_concurrency_change(self, _=None):
        self.lbl_concurrency.configure(text=str(self.concurrency.get()))

    def _browse_output(self):
        d = filedialog.askdirectory(initialdir=self.output_dir.get() or os.getcwd(),
                                    title="选择输出目录")
        if d:
            self.output_dir.set(d)

    def _open_output(self):
        path = self.output_dir.get()
        if path and os.path.exists(path):
            try:
                os.startfile(path)
            except Exception:
                pass
        else:
            messagebox.showinfo("提示", "输出目录不存在, 请先选择有效目录。")

    # ---------------- 日志 (缓冲式, 由 _tick 批量刷新) ----------------
    def log(self, msg):
        ts = datetime.now().strftime("%H:%M:%S")
        with self._log_lock:
            self._log_buffer.append(f"[{ts}] {msg}")
        self._ensure_tick()

    def _ensure_tick(self):
        """启动节流刷新循环 (仅启动一次)."""
        if not self._tick_running:
            self._tick_running = True
            self.after(120, self._tick)

    def _tick(self):
        """定时 (120ms) 将缓冲日志批量写入 + 刷新进度条.
        将高并发下载时的 ~2000 次 after() 降到 ~8 次/秒, UI 不卡顿."""
        # 批量刷日志
        with self._log_lock:
            if self._log_buffer:
                text = "\n".join(self._log_buffer) + "\n"
                self._log_buffer.clear()
            else:
                text = ""
        if text:
            self._log_insert(text)
        # 下载中则刷新进度
        if self.downloading:
            self._refresh_progress()
            self.after(120, self._tick)
        else:
            # 下载结束后: 若仍有残余日志则再刷一次后停止
            self._tick_running = False
            if text:
                pass  # 已刷
            with self._log_lock:
                leftover = bool(self._log_buffer)
            if leftover:
                self._ensure_tick()

    def _log_insert(self, text):
        self.logbox.configure(state="normal")
        self.logbox.insert("end", text)
        self.logbox.see("end")
        self.logbox.configure(state="disabled")

    def _refresh_progress(self):
        s = self._stats
        t = self._total
        self.progress.set(s["done"] / t if t else 0)
        self.lbl_stat.configure(
            text=f"进度 {s['done']}/{t}  ✓{s['success']}  "
                 f"✗{s['failed']}  ⤳{s['skipped']}")

    # ---------------- 列表抓取 ----------------
    def _refresh_list_threaded(self):
        if self.fetching or self.downloading:
            return
        self.fetching = True
        self.btn_download.configure(state="disabled")
        self.log("开始抓取舰船图鉴列表 …")

        def work():
            try:
                ships = fetch_ship_list(progress_cb=lambda m: self.log(m))
                self.after(0, lambda: self._on_list_fetched(ships))
            except Exception as e:
                self.after(0, lambda: self._on_fetch_error(e))

        threading.Thread(target=work, daemon=True).start()

    def _on_fetch_error(self, e):
        self.fetching = False
        self.btn_download.configure(state="normal")
        self.log(f"抓取失败: {e}")
        messagebox.showerror("抓取失败",
                             f"获取图鉴页面失败:\n{e}\n\n请检查网络后点击「刷新列表」重试。")

    def _on_list_fetched(self, ships):
        self.fetching = False
        self.btn_download.configure(state="normal")
        self.ships = ships
        self._populate_list()
        self.log(f"列表加载完成, 共 {len(ships)} 艘舰娘。")

    def _populate_list(self):
        # 清空旧控件
        for w in self.scroll.winfo_children():
            w.destroy()
        self.ship_rows = []

        # 收集筛选项
        factions, types, rarities = set(), set(), set()
        for s in self.ships:
            if s.faction:
                factions.add(s.faction)
            if s.ship_type:
                types.add(s.ship_type)
            if s.rarity:
                rarities.add(s.rarity)

        def sort_key(v):
            return (RARITY_ORDER.index(v) if v in RARITY_ORDER
                    else len(RARITY_ORDER), v)
        factions = sorted(factions)
        types = sorted(types)
        rarities = sorted(rarities, key=sort_key)

        self.om_faction.configure(values=["全部"] + factions)
        self.om_type.configure(values=["全部"] + types)
        self.om_rarity.configure(values=["全部"] + rarities)
        self.filter_faction.set("全部")
        self.filter_type.set("全部")
        self.filter_rarity.set("全部")

        # 创建行 (每行一个 CTkFrame, 内含 checkbox + 标签)
        for s in self.ships:
            var = ctk.BooleanVar(value=True)
            row = ctk.CTkFrame(self.scroll, fg_color="transparent")
            cb = ctk.CTkCheckBox(row, text=s.name, variable=var,
                                 font=ctk.CTkFont(size=13))
            cb.pack(side="left")
            tags = []
            if s.ship_type:
                tags.append(s.ship_type)
            if s.rarity:
                tags.append(s.rarity)
            if s.faction:
                tags.append(s.faction)
            tag_text = "  ·  ".join(tags) if tags else "—"
            color = RARITY_COLORS.get(s.rarity, "#7a8a99")
            ctk.CTkLabel(row, text=tag_text,
                         font=ctk.CTkFont(size=11),
                         text_color=color).pack(side="left", padx=(8, 0))
            self.ship_rows.append((s, row, var))

        self._relayout_grid()
        self._update_count()

    def _relayout_grid(self):
        """根据滚动区宽度决定列数 (双列布局)."""
        try:
            w = self.scroll.winfo_width()
        except Exception:
            w = 700
        cols = 2 if w > 520 else 1
        for i, (_, row, _) in enumerate(self.ship_rows):
            if row.winfo_ismapped():
                row.grid_forget()
            row.grid(row=i // cols, column=i % cols, sticky="ew", padx=4, pady=1)
        # 让列等宽
        for c in range(cols):
            self.scroll.grid_columnconfigure(c, weight=1, uniform="col")

    # ---------------- 筛选 ----------------
    def _apply_filter(self):
        kw = self.search_text.get().strip().lower()
        fa = self.filter_faction.get()
        ty = self.filter_type.get()
        ra = self.filter_rarity.get()
        visible = 0
        for s, row, var in self.ship_rows:
            ok = True
            if kw and kw not in s.name.lower():
                ok = False
            if fa != "全部" and s.faction != fa:
                ok = False
            if ty != "全部" and s.ship_type != ty:
                ok = False
            if ra != "全部" and s.rarity != ra:
                ok = False
            if ok:
                row.grid()
                visible += 1
            else:
                row.grid_remove()
        self._update_count()

    # ---------------- 选择 ----------------
    def _select_all(self):
        for s, row, var in self.ship_rows:
            if row.winfo_ismapped():
                var.set(True)
        self._update_count()

    def _select_none(self):
        for s, row, var in self.ship_rows:
            if row.winfo_ismapped():
                var.set(False)
        self._update_count()

    def _select_invert(self):
        for s, row, var in self.ship_rows:
            if row.winfo_ismapped():
                var.set(not var.get())
        self._update_count()

    def _update_count(self):
        total = sum(1 for _, row, _ in self.ship_rows if row.winfo_ismapped())
        selected = sum(1 for _, row, var in self.ship_rows
                       if row.winfo_ismapped() and var.get())
        self.lbl_count.configure(text=f"可见: {total}  |  已选: {selected}")

    # ---------------- 下载 ----------------
    def _start_download(self):
        if self.downloading:
            return
        selected = [s for s, row, var in self.ship_rows if var.get()]
        if not selected:
            messagebox.showwarning("提示", "请至少选择一艘舰娘。")
            return

        out_root = self.output_dir.get().strip()
        if not out_root:
            messagebox.showwarning("提示", "请选择输出目录。")
            return
        try:
            os.makedirs(out_root, exist_ok=True)
        except Exception as e:
            messagebox.showerror("错误", f"无法创建输出目录:\n{e}")
            return

        # 决定子文件夹
        mode = self.subfolder_mode.get()
        if mode == "时间戳":
            sub = datetime.now().strftime("%Y%m%d_%H%M%S")
        elif mode == "自定义":
            sub = self.custom_subfolder.get().strip()
            if not sub:
                messagebox.showwarning("提示", "请输入自定义子文件夹名称。")
                return
            sub = sanitize_filename(sub)
        else:
            sub = ""

        target_dir = os.path.join(out_root, sub) if sub else out_root
        os.makedirs(target_dir, exist_ok=True)

        # manifest 路径 (始终在输出根目录)
        manifest_path = os.path.join(out_root, MANIFEST_NAME)
        manifest = load_manifest(manifest_path)

        self.downloading = True
        self.btn_download.configure(state="disabled", text="下载中…")
        self.progress.set(0)

        # 重置统计 (实例级, _tick 直接读取, 无需 after 传参)
        self._stats = {"success": 0, "failed": 0, "skipped": 0, "done": 0, "bytes": 0}
        self._total = len(selected)
        stats = self._stats
        total = self._total

        def work():
            try:
                self._run_download(selected, target_dir, out_root, sub,
                                   manifest, manifest_path)
            finally:
                self.after(0, lambda: self._on_download_done(stats, total,
                                                              target_dir))

        threading.Thread(target=work, daemon=True).start()

    def _run_download(self, ships, target_dir, out_root, sub,
                      manifest, manifest_path):
        workers = self.concurrency.get()
        skip = self.skip_existing.get()
        grab_border = self.download_border.get()
        stats = self._stats

        # 预计算文件名/路径, 减少 do_one 内开销
        existing = manifest["ships"] if skip else {}

        def do_one(ship):
            """纯函数: 下载单艘舰, 返回结果 dict. 不触碰共享 stats/manifest,
            由 as_completed 单消费者线程聚合, 彻底消除锁竞争与竞态."""
            name = ship.name
            ext = get_extension(ship.avatar_url)
            fname = f"{sanitize_filename(name)}.{ext}"
            fpath = os.path.join(target_dir, fname)

            if skip and name in existing:
                return {"st": "skip", "name": name, "msg": f"跳过 {name} (已下载)"}

            ok = self._download_file(ship.avatar_url, fpath)
            border_ok = True
            if ok and grab_border and ship.border_url:
                bname = f"{sanitize_filename(name)}_border.png"
                bpath = os.path.join(target_dir, bname)
                border_ok = self._download_file(ship.border_url, bpath)

            if ok and border_ok:
                size = os.path.getsize(fpath)
                entry = {
                    "filename": fname,
                    "url": ship.avatar_url,
                    "subfolder": sub,
                    "rarity": ship.rarity,
                    "faction": ship.faction,
                    "ship_type": ship.ship_type,
                    "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                }
                return {"st": "ok", "name": name, "entry": entry,
                        "size": size, "fname": fname,
                        "msg": f"✓ {name} -> {fname} ({size//1024}KB)"}
            return {"st": "fail", "name": name, "msg": f"✗ {name} 下载失败"}

        # submit + as_completed: 单消费者线程聚合结果, 无锁无竞态
        manifest_dirty = 0
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = [pool.submit(do_one, s) for s in ships]
            for fut in as_completed(futures):
                r = fut.result()
                st = r["st"]
                if st == "skip":
                    stats["skipped"] += 1
                elif st == "ok":
                    stats["success"] += 1
                    stats["bytes"] += r["size"]
                    manifest["ships"][r["name"]] = r["entry"]
                    manifest_dirty += 1
                    if manifest_dirty >= 20:
                        save_manifest(manifest_path, manifest)
                        manifest_dirty = 0
                else:
                    stats["failed"] += 1
                stats["done"] += 1
                self.log(r["msg"])

        # 最终保存 manifest
        save_manifest(manifest_path, manifest)
        self.log(f"manifest 已保存: {manifest_path}")

    def _download_file(self, url, fpath, retries=3):
        """下载单个文件 (复用全局 Session 连接池), 带指数退避重试."""
        tmp = fpath + ".part"
        for attempt in range(retries):
            try:
                resp = get_http_session().get(url, timeout=(8, 15), stream=True)
                resp.raise_for_status()
                with open(tmp, "wb") as f:
                    for chunk in resp.iter_content(65536):
                        if chunk:
                            f.write(chunk)
                os.replace(tmp, fpath)
                return True
            except Exception:
                # 指数退避: 0.3s / 0.6s / 1.2s (比旧版 0.8/1.6/2.4 快)
                time.sleep(0.3 * (2 ** attempt))
        # 清理残留 .part
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except Exception:
            pass
        return False

    def _on_download_done(self, stats, total, target_dir):
        self.downloading = False
        self.btn_download.configure(state="normal", text="开始下载")
        self.progress.set(1.0)
        mb = stats["bytes"] / 1024 / 1024
        self.lbl_stat.configure(
            text=f"完成 {stats['done']}/{total}  ✓{stats['success']}  "
                 f"✗{stats['failed']}  ⤳{stats['skipped']}  {mb:.1f}MB")
        self.log(f"下载完成: 成功 {stats['success']}, 失败 {stats['failed']}, "
                 f"跳过 {stats['skipped']}, 共 {mb:.1f}MB")
        self.log(f"输出目录: {target_dir}")
        if stats["failed"] > 0:
            messagebox.showinfo("下载完成",
                                f"下载完成, 但有 {stats['failed']} 个失败。\n"
                                f"请查看日志了解详情, 可重新选择失败项重试。")
        else:
            messagebox.showinfo("下载完成",
                                f"全部完成!\n成功 {stats['success']} 个, "
                                f"跳过 {stats['skipped']} 个。")


# ============================================================
#  入口
# ============================================================
def main():
    app = AvatarDownloaderApp()
    app.mainloop()


if __name__ == "__main__":
    main()
