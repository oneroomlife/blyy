#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
碧蓝航线 SD 资源整理台 — Web 图形界面
======================================

基于 Python 标准库 http.server 的本地 Web GUI，无需额外依赖。
运行 `python organize_sd.py --gui` 或 `python organize_sd_gui.py` 启动，
自动打开浏览器访问 http://127.0.0.1:<port>

视觉风格：「青花瓷 · Blue & White Porcelain」
瓷白胎体 + 钴蓝釉下彩 + 朱砂印泥 + 缠枝莲纹。
"""

import json
import socket
import sys
import traceback
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# 确保能 import organize_sd 模块
SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from organize_sd import (
    scan_source, build_manifest, copy_groups, write_manifest,
    SKEL_EXT, ATLAS_EXT, PNG_EXT,
)

PROJECT_ROOT = SCRIPT_DIR.parent
DEFAULT_SRC = PROJECT_ROOT / 'sd'
DEFAULT_OUT = DEFAULT_SRC / 'organized'


# ──────────────────────────────────────────────────────────────
# HTML 页面（青花瓷 · Blue & White Porcelain 视觉风格）
# ──────────────────────────────────────────────────────────────

HTML_CONTENT = r'''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>青花瓷 · SD 资源整理台</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@400;500;600;700;900&family=Ma+Shan+Zheng&family=ZCOOL+XiaoWei&family=Noto+Sans+SC:wght@300;400;500;600&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
:root {
  /* 瓷胎釉色 */
  --porcelain: #f5f0e6;
  --porcelain-bright: #fbf7ee;
  --porcelain-warm: #ede4d0;
  --porcelain-shadow: #d9cfb8;
  /* 钴蓝釉下彩 */
  --cobalt: #1e3a8a;
  --cobalt-deep: #172554;
  --cobalt-bright: #2c5cc7;
  --cobalt-soft: #4f7fd9;
  --indigo: #1e40af;
  --qing: #0e4c92;
  /* 朱砂印泥 */
  --cinnabar: #c1272d;
  --cinnabar-deep: #8b1a1f;
  --vermilion: #e63946;
  /* 墨色 */
  --ink: #1a1a2e;
  --ink-soft: #3a3a4e;
  --ash: #6b6b7a;
  --mist: #9a9aa8;
  /* 自然色 */
  --jade: #4a7c59;
  --gold: #b8860b;
  --gold-soft: #d4a843;
  --success: #2d6a4f;
  --warning: #b8860b;
  --error: #c1272d;
  /* 面板 */
  --panel-bg: rgba(251, 247, 238, 0.92);
  --panel-edge: rgba(245, 240, 230, 0.85);
  --field-bg: rgba(255, 255, 255, 0.65);
  --field-bg-focus: rgba(255, 255, 255, 0.92);
  --border: rgba(30, 58, 138, 0.22);
  --border-bright: rgba(30, 58, 138, 0.55);
  --border-soft: rgba(30, 58, 138, 0.12);
}

* { box-sizing: border-box; margin: 0; padding: 0; }

html { scroll-behavior: smooth; }

body {
  font-family: 'Noto Sans SC', sans-serif;
  color: var(--ink);
  background: var(--porcelain);
  min-height: 100vh;
  overflow-x: hidden;
  line-height: 1.6;
  position: relative;
}

/* ─── 背景层：瓷胎釉色 ─── */
.bg-base {
  position: fixed; inset: 0; z-index: -5;
  background:
    radial-gradient(ellipse 80% 60% at 50% 0%, rgba(30,58,138,0.06), transparent 70%),
    radial-gradient(ellipse 60% 50% at 90% 100%, rgba(193,39,45,0.04), transparent 70%),
    radial-gradient(ellipse 50% 60% at 10% 80%, rgba(14,76,146,0.05), transparent 70%),
    linear-gradient(180deg, var(--porcelain-bright) 0%, var(--porcelain) 50%, var(--porcelain-warm) 100%);
}

/* 缠枝莲纹网格 */
.bg-pattern {
  position: fixed; inset: 0; z-index: -4;
  opacity: 0.45;
  pointer-events: none;
}

/* 纸张纹理 */
.bg-grain {
  position: fixed; inset: 0; z-index: -3;
  pointer-events: none;
  opacity: 0.35;
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/><feColorMatrix values='0 0 0 0 0.12 0 0 0 0 0.22 0 0 0 0 0.54 0 0 0 0.08 0'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>");
  mix-blend-mode: multiply;
}

/* 浮动云纹 */
.bg-cloud {
  position: fixed;
  z-index: -2;
  pointer-events: none;
  opacity: 0.06;
}

.bg-cloud.c1 { top: 8%; left: -120px; width: 360px; animation: drift1 80s linear infinite; }
.bg-cloud.c2 { top: 45%; right: -120px; width: 320px; animation: drift2 110s linear infinite; }
.bg-cloud.c3 { bottom: 12%; left: 10%; width: 280px; animation: drift1 95s linear infinite reverse; }

@keyframes drift1 {
  0% { transform: translateX(0) translateY(0); }
  50% { transform: translateX(80px) translateY(-20px); }
  100% { transform: translateX(0) translateY(0); }
}

@keyframes drift2 {
  0% { transform: translateX(0) translateY(0); }
  50% { transform: translateX(-60px) translateY(20px); }
  100% { transform: translateX(0) translateY(0); }
}

/* ─── 头部 ─── */
.porcelain-header {
  text-align: center;
  padding: 84px 24px 40px;
  position: relative;
}

/* 头部缠枝花纹边框 */
.header-ornament {
  width: 200px;
  height: 28px;
  margin: 0 auto 28px;
  display: block;
}

.header-divider {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 24px;
  margin-bottom: 24px;
}

.header-divider .line {
  width: 110px; height: 1px;
  background: linear-gradient(90deg, transparent, var(--cobalt-soft), var(--cobalt));
}

.header-divider .line.right {
  background: linear-gradient(90deg, var(--cobalt), var(--cobalt-soft), transparent);
}

.header-divider .seal {
  width: 36px; height: 36px;
  background: var(--cinnabar);
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: 'Ma Shan Zheng', cursive;
  color: var(--porcelain-bright);
  font-size: 1.125rem;
  font-weight: 700;
  box-shadow:
    0 2px 8px rgba(193,39,45,0.35),
    inset 0 0 0 2px rgba(255,255,255,0.15);
  transform: rotate(-3deg);
  position: relative;
}

.header-divider .seal::before {
  content: '';
  position: absolute;
  inset: -5px;
  border: 1px dashed var(--cinnabar);
  border-radius: 6px;
  opacity: 0.4;
}

.header-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 4.25rem;
  font-weight: 900;
  letter-spacing: 0.25em;
  color: var(--cobalt-deep);
  line-height: 1;
  text-shadow: 0 2px 0 var(--porcelain-shadow);
  position: relative;
  display: inline-block;
}

.header-title .accent {
  color: var(--cinnabar);
  font-family: 'Ma Shan Zheng', cursive;
  font-weight: 400;
}

.header-subtitle {
  font-family: 'ZCOOL XiaoWei', serif;
  font-size: 1.25rem;
  letter-spacing: 0.35em;
  color: var(--cobalt);
  margin-top: 18px;
  font-weight: 400;
}

.header-poem {
  font-family: 'Noto Serif SC', serif;
  font-size: 0.9375rem;
  color: var(--ash);
  letter-spacing: 0.2em;
  margin-top: 20px;
  font-style: italic;
  line-height: 1.8;
}

.header-poem .author {
  color: var(--cinnabar);
  font-size: 0.8125rem;
  margin-left: 8px;
}

/* ─── 主区域 ─── */
.porcelain-room {
  max-width: 1480px;
  margin: 0 auto;
  padding: 0 28px 64px;
  display: grid;
  grid-template-columns: 400px 1fr;
  gap: 28px;
  align-items: start;
}

@media (max-width: 960px) {
  .porcelain-room { grid-template-columns: 1fr; }
}

/* ─── 瓷盘面板 ─── */
.panel {
  background: var(--panel-bg);
  backdrop-filter: blur(18px) saturate(130%);
  -webkit-backdrop-filter: blur(18px) saturate(130%);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 36px;
  position: relative;
  box-shadow:
    0 12px 36px rgba(30,58,138,0.1),
    0 2px 8px rgba(0,0,0,0.04),
    inset 0 1px 0 rgba(255,255,255,0.7);
}

/* 钴蓝边框装饰角 */
.panel::before,
.panel::after {
  content: '';
  position: absolute;
  width: 32px; height: 32px;
  border: 2px solid var(--cobalt);
  opacity: 0.6;
  pointer-events: none;
  border-radius: 2px;
}

.panel::before {
  top: 12px; left: 12px;
  border-right: none; border-bottom: none;
}

.panel::after {
  bottom: 12px; right: 12px;
  border-left: none; border-top: none;
}

/* 回纹边框装饰 */
.panel-border-top,
.panel-border-bottom {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  width: 60%;
  height: 6px;
  pointer-events: none;
  opacity: 0.3;
}

.panel-border-top { top: 0; }
.panel-border-bottom { bottom: 0; }

.panel-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.625rem;
  font-weight: 700;
  letter-spacing: 0.2em;
  color: var(--cobalt-deep);
  margin: 0 0 28px;
  padding-bottom: 22px;
  border-bottom: 2px solid var(--cobalt);
  display: flex;
  align-items: center;
  gap: 16px;
  position: relative;
}

.panel-title::after {
  content: '';
  position: absolute;
  bottom: -2px; left: 0;
  width: 80px; height: 2px;
  background: var(--cinnabar);
}

.panel-title .index {
  font-family: 'Ma Shan Zheng', cursive;
  font-size: 1.25rem;
  color: var(--cinnabar);
  letter-spacing: 0;
  font-weight: 400;
  background: rgba(193,39,45,0.08);
  border: 1px solid var(--cinnabar);
  padding: 2px 12px;
  border-radius: 3px;
}

/* ─── 表单字段 ─── */
.field-group { margin-bottom: 24px; }

.field-label {
  display: block;
  font-family: 'Noto Serif SC', serif;
  font-size: 1rem;
  font-weight: 500;
  letter-spacing: 0.1em;
  color: var(--cobalt-deep);
  margin-bottom: 10px;
}

.field-label .num {
  color: var(--cinnabar);
  font-family: 'JetBrains Mono', monospace;
  margin-right: 10px;
  font-size: 0.8125rem;
  font-weight: 500;
}

.input-field {
  width: 100%;
  background: var(--field-bg);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 13px 16px;
  color: var(--ink);
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.8125rem;
  transition: all 0.25s;
  box-shadow: inset 0 1px 3px rgba(30,58,138,0.06);
}

.input-field:focus {
  outline: none;
  border-color: var(--cobalt);
  background: var(--field-bg-focus);
  box-shadow:
    inset 0 1px 3px rgba(30,58,138,0.06),
    0 0 0 3px rgba(30,58,138,0.12),
    0 2px 8px rgba(30,58,138,0.1);
}

.input-field::placeholder {
  color: var(--mist);
  opacity: 0.7;
}

/* ─── 开关 ─── */
.options-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 28px;
}

.toggle {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  padding: 12px 16px;
  border-radius: 4px;
  background: var(--field-bg);
  border: 1px solid var(--border);
  transition: all 0.25s;
  font-size: 0.9375rem;
  user-select: none;
  position: relative;
}

.toggle:hover {
  border-color: var(--border-bright);
  background: rgba(255,255,255,0.85);
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(30,58,138,0.08);
}

.toggle input { display: none; }

.toggle .lever {
  width: 38px; height: 20px;
  background: var(--porcelain-shadow);
  border: 1px solid var(--border);
  border-radius: 10px;
  position: relative;
  transition: all 0.3s;
  flex-shrink: 0;
}

.toggle .lever::after {
  content: '';
  position: absolute;
  top: 1px; left: 1px;
  width: 16px; height: 16px;
  border-radius: 50%;
  background: var(--porcelain-bright);
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
  transition: all 0.3s;
}

.toggle input:checked + .lever {
  background: var(--cobalt);
  border-color: var(--cobalt-deep);
}

.toggle input:checked + .lever::after {
  left: 19px;
  background: var(--porcelain-bright);
  box-shadow: 0 1px 4px rgba(0,0,0,0.25), 0 0 8px rgba(79,127,217,0.6);
}

.toggle input:checked ~ .toggle-label {
  color: var(--cobalt-deep);
  font-weight: 600;
}

.toggle-label {
  font-family: 'Noto Serif SC', serif;
  font-size: 1rem;
  letter-spacing: 0.05em;
  transition: color 0.2s;
  color: var(--ash);
}

/* ─── 按钮 ─── */
.btn-row {
  display: flex;
  gap: 14px;
}

.btn {
  flex: 1;
  padding: 16px 24px;
  border: none;
  border-radius: 4px;
  font-family: 'Noto Serif SC', serif;
  font-size: 1.0625rem;
  font-weight: 600;
  letter-spacing: 0.3em;
  cursor: pointer;
  transition: all 0.3s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  position: relative;
  overflow: hidden;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 主按钮：钴蓝釉下彩 + 光扫 */
.btn-primary {
  background: linear-gradient(135deg, var(--cobalt-bright) 0%, var(--cobalt) 50%, var(--cobalt-deep) 100%);
  color: var(--porcelain-bright);
  box-shadow:
    0 4px 14px rgba(30,58,138,0.3),
    inset 0 1px 0 rgba(255,255,255,0.25),
    inset 0 -1px 0 rgba(23,37,84,0.5);
  border: 1px solid var(--cobalt-deep);
}

.btn-primary::before {
  content: '';
  position: absolute;
  top: 0; left: -100%;
  width: 100%; height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,0.35), transparent);
  transition: left 0.7s;
}

.btn-primary:hover:not(:disabled)::before {
  left: 100%;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow:
    0 8px 24px rgba(30,58,138,0.4),
    inset 0 1px 0 rgba(255,255,255,0.3),
    inset 0 -1px 0 rgba(23,37,84,0.5);
}

.btn-primary:active:not(:disabled) {
  transform: translateY(0);
}

/* 次按钮：瓷白胎 + 钴蓝描边 */
.btn-secondary {
  background: var(--porcelain-bright);
  border: 1.5px solid var(--cobalt);
  color: var(--cobalt-deep);
  box-shadow: 0 2px 6px rgba(30,58,138,0.08);
}

.btn-secondary:hover:not(:disabled) {
  background: var(--porcelain);
  border-color: var(--cobalt-deep);
  color: var(--cobalt-deep);
  transform: translateY(-2px);
  box-shadow: 0 6px 18px rgba(30,58,138,0.18);
}

.btn .spinner {
  width: 16px; height: 16px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

/* ─── 进度条 ─── */
.progress-bar {
  height: 3px;
  background: var(--porcelain-shadow);
  border-radius: 2px;
  overflow: hidden;
  margin-top: 20px;
  position: relative;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--cobalt-soft), var(--cobalt), var(--cinnabar));
  border-radius: 2px;
  transition: width 0.4s ease;
  box-shadow: 0 0 12px rgba(30,58,138,0.4);
  position: relative;
}

.progress-fill::after {
  content: '';
  position: absolute;
  top: 0; right: 0;
  width: 30px; height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,0.7));
  animation: progressShimmer 1.5s linear infinite;
}

@keyframes progressShimmer {
  from { transform: translateX(30px); }
  to { transform: translateX(-100px); }
}

/* ─── 结果区 ─── */
.result-area { min-height: 540px; }

.placeholder {
  text-align: center;
  padding: 100px 24px;
  color: var(--ash);
}

.placeholder .vase {
  width: 72px; height: 80px;
  margin: 0 auto 28px;
  opacity: 0.4;
  animation: floatY 4.5s ease-in-out infinite;
}

@keyframes floatY {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-10px); }
}

.placeholder p {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.125rem;
  letter-spacing: 0.12em;
  color: var(--ash);
}

.placeholder p .hint {
  color: var(--cinnabar);
  font-weight: 500;
}

/* ─── 统计卡片 ─── */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 16px;
  margin-bottom: 28px;
}

.stat-card {
  background: var(--field-bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 22px 14px;
  text-align: center;
  transition: all 0.3s;
  position: relative;
  overflow: hidden;
}

.stat-card::before {
  content: '';
  position: absolute;
  top: 0; left: 50%;
  transform: translateX(-50%);
  width: 50%; height: 2px;
  background: linear-gradient(90deg, transparent, var(--cobalt), transparent);
}

.stat-card::after {
  content: '';
  position: absolute;
  bottom: 0; left: 50%;
  transform: translateX(-50%);
  width: 30%; height: 1px;
  background: linear-gradient(90deg, transparent, var(--cinnabar), transparent);
}

.stat-card:hover {
  border-color: var(--border-bright);
  background: rgba(255,255,255,0.85);
  transform: translateY(-3px);
  box-shadow: 0 8px 22px rgba(30,58,138,0.12);
}

.stat-value {
  font-family: 'Noto Serif SC', serif;
  font-size: 2.75rem;
  font-weight: 900;
  color: var(--cobalt-deep);
  line-height: 1;
  letter-spacing: 0.02em;
}

.stat-label {
  font-family: 'Noto Serif SC', serif;
  font-size: 0.9375rem;
  color: var(--ash);
  letter-spacing: 0.18em;
  margin-top: 12px;
  font-weight: 500;
}

/* ─── 搜索框 ─── */
.search-bar {
  margin-bottom: 16px;
  position: relative;
}

.search-bar .input-field {
  padding-left: 44px;
  padding-right: 60px;
}

.search-bar .search-icon {
  position: absolute;
  left: 14px; top: 50%;
  transform: translateY(-50%);
  color: var(--cobalt);
  pointer-events: none;
}

.search-bar .kbd-hint {
  position: absolute;
  right: 12px; top: 50%;
  transform: translateY(-50%);
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.6875rem;
  color: var(--ash);
  background: var(--porcelain-warm);
  border: 1px solid var(--border);
  padding: 2px 6px;
  border-radius: 3px;
  pointer-events: none;
}

/* ─── 树形视图（舰船名册） ─── */
.tree-container {
  background: rgba(255,255,255,0.5);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 20px;
  max-height: 680px;
  overflow-y: auto;
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.8125rem;
  line-height: 1.9;
}

.tree-container::-webkit-scrollbar { width: 8px; }
.tree-container::-webkit-scrollbar-track { background: var(--porcelain-warm); border-radius: 4px; }
.tree-container::-webkit-scrollbar-thumb {
  background: var(--cobalt-soft);
  border-radius: 4px;
}
.tree-container::-webkit-scrollbar-thumb:hover { background: var(--cobalt); }

.tree-node {
  padding-left: 26px;
  position: relative;
}

.tree-node::before {
  content: '';
  position: absolute;
  left: 9px; top: 0; bottom: 0;
  border-left: 1px solid var(--border-soft);
}

.tree-node:last-child::before { bottom: 50%; }

.tree-node::after {
  content: '';
  position: absolute;
  left: 9px; top: 50%;
  width: 14px; height: 1px;
  border-top: 1px solid var(--border-soft);
}

.tree-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border-radius: 3px;
  cursor: default;
  transition: all 0.15s;
}

.tree-row:hover {
  background: rgba(30,58,138,0.08);
  padding-left: 12px;
}

.tree-row.expandable {
  cursor: pointer;
  user-select: none;
}

.tree-arrow {
  width: 14px; text-align: center;
  color: var(--cobalt);
  transition: transform 0.25s;
  font-size: 0.6875rem;
}

.tree-node.collapsed > .tree-row .tree-arrow {
  transform: rotate(-90deg);
}
.tree-node.collapsed > .tree-children { display: none; }

.tree-icon {
  width: 20px; text-align: center;
  font-size: 0.9375rem;
}

.tree-icon.skel { color: var(--cobalt); }
.tree-icon.atlas { color: var(--cinnabar); }
.tree-icon.png { color: var(--jade); }
.tree-icon.warning { color: var(--warning); }
.tree-icon.ship { color: var(--cinnabar-deep); }
.tree-icon.skin { color: var(--qing); }

.tree-ship-name {
  font-family: 'Noto Serif SC', serif;
  font-weight: 700;
  font-size: 1.0625rem;
  letter-spacing: 0.05em;
  color: var(--cobalt-deep);
}

.tree-skin-name {
  font-family: 'ZCOOL XiaoWei', serif;
  font-size: 1rem;
  color: var(--qing);
  letter-spacing: 0.05em;
}

.tree-badge {
  font-size: 0.6875rem;
  color: var(--ash);
  background: var(--porcelain-warm);
  border: 1px solid var(--border);
  padding: 2px 8px;
  border-radius: 3px;
  margin-left: 6px;
  letter-spacing: 0.05em;
  font-family: 'JetBrains Mono', monospace;
}

.tree-note {
  font-family: 'Noto Serif SC', serif;
  font-size: 0.8125rem;
  color: var(--warning);
  margin-left: 8px;
  opacity: 0.85;
}

/* ─── Toast ─── */
.toast-container {
  position: fixed;
  top: 28px; right: 28px;
  z-index: 1000;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toast {
  background: var(--panel-bg);
  backdrop-filter: blur(20px);
  border: 1px solid var(--border-bright);
  border-radius: 4px;
  padding: 14px 22px;
  font-family: 'Noto Serif SC', serif;
  font-size: 0.9375rem;
  color: var(--ink);
  box-shadow: 0 12px 36px rgba(30,58,138,0.18);
  animation: slideIn 0.35s cubic-bezier(0.2, 0.9, 0.3, 1.2);
  max-width: 460px;
  display: flex;
  align-items: center;
  gap: 12px;
  position: relative;
  overflow: hidden;
}

.toast::before {
  content: '';
  width: 4px; height: 100%;
  position: absolute;
  left: 0; top: 0;
}

.toast.success::before { background: var(--success); box-shadow: 0 0 12px var(--success); }
.toast.error::before { background: var(--error); box-shadow: 0 0 12px var(--error); }
.toast.warning::before { background: var(--warning); box-shadow: 0 0 12px var(--warning); }
.toast.info::before { background: var(--cobalt); box-shadow: 0 0 12px var(--cobalt); }

.toast .toast-icon {
  width: 22px; height: 22px;
  flex-shrink: 0;
}

@keyframes slideIn {
  from { transform: translateX(120%); opacity: 0; }
  to { transform: translateX(0); opacity: 1; }
}

.toast.removing { animation: slideOut 0.3s ease forwards; }

@keyframes slideOut {
  to { transform: translateX(120%); opacity: 0; }
}

/* ─── 指南 ─── */
.guide-section {
  max-width: 1480px;
  margin: 0 auto 72px;
  padding: 0 28px;
}

.guide-card {
  background: var(--panel-bg);
  backdrop-filter: blur(16px);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 40px;
  position: relative;
  box-shadow: 0 8px 28px rgba(30,58,138,0.08);
}

.guide-card::before,
.guide-card::after {
  content: '';
  position: absolute;
  width: 32px; height: 32px;
  border: 2px solid var(--cobalt);
  opacity: 0.5;
  pointer-events: none;
  border-radius: 2px;
}

.guide-card::before {
  top: 12px; left: 12px;
  border-right: none; border-bottom: none;
}

.guide-card::after {
  bottom: 12px; right: 12px;
  border-left: none; border-top: none;
}

.guide-card h3 {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.5rem;
  font-weight: 700;
  letter-spacing: 0.25em;
  color: var(--cobalt-deep);
  margin: 0 0 32px;
  padding-bottom: 20px;
  border-bottom: 2px solid var(--cobalt);
  position: relative;
}

.guide-card h3::after {
  content: '';
  position: absolute;
  bottom: -2px; left: 0;
  width: 80px; height: 2px;
  background: var(--cinnabar);
}

.guide-steps {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 28px;
}

.guide-step {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.step-num {
  font-family: 'Ma Shan Zheng', cursive;
  font-size: 2.5rem;
  color: var(--cinnabar);
  flex-shrink: 0;
  width: 48px;
  line-height: 1;
  position: relative;
}

.step-num::after {
  content: '';
  position: absolute;
  bottom: -8px; left: 0;
  width: 32px; height: 2px;
  background: var(--cinnabar);
  opacity: 0.5;
}

.step-text {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.0625rem;
  color: var(--ink-soft);
  line-height: 1.75;
  padding-top: 8px;
}

.step-text strong {
  color: var(--cobalt-deep);
  font-weight: 700;
  letter-spacing: 0.02em;
}

.step-text code {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.8125rem;
  background: var(--porcelain-warm);
  padding: 3px 8px;
  border-radius: 3px;
  color: var(--cinnabar-deep);
  border: 1px solid var(--border);
}

/* ─── 页脚 ─── */
.porcelain-footer {
  text-align: center;
  padding: 0 24px 56px;
  font-family: 'Noto Serif SC', serif;
  font-size: 0.9375rem;
  color: var(--ash);
  letter-spacing: 0.15em;
}

.porcelain-footer .crest {
  width: 40px; height: 40px;
  margin: 0 auto 18px;
  opacity: 0.5;
}

.porcelain-footer .seal-mini {
  display: inline-block;
  background: var(--cinnabar);
  color: var(--porcelain-bright);
  font-family: 'Ma Shan Zheng', cursive;
  font-size: 0.875rem;
  padding: 3px 10px;
  border-radius: 3px;
  margin: 0 6px;
  transform: rotate(-2deg);
  vertical-align: middle;
}

/* ─── 入场动画 ─── */
@keyframes fadeUp {
  from { opacity: 0; transform: translateY(28px); }
  to { opacity: 1; transform: translateY(0); }
}

.porcelain-header { animation: fadeUp 1s 0.1s cubic-bezier(0.2, 0.8, 0.3, 1) backwards; }
.porcelain-room .panel:nth-child(1) { animation: fadeUp 1s 0.3s cubic-bezier(0.2, 0.8, 0.3, 1) backwards; }
.porcelain-room .panel:nth-child(2) { animation: fadeUp 1s 0.45s cubic-bezier(0.2, 0.8, 0.3, 1) backwards; }
.guide-section { animation: fadeUp 1s 0.6s cubic-bezier(0.2, 0.8, 0.3, 1) backwards; }
.porcelain-footer { animation: fadeUp 1s 0.75s cubic-bezier(0.2, 0.8, 0.3, 1) backwards; }

/* 减弱动画偏好 */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
  .bg-cloud { display: none; }
}
</style>
</head>
<body>

<!-- 背景层 -->
<div class="bg-base"></div>

<!-- 缠枝莲纹网格 -->
<svg class="bg-pattern" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 240" preserveAspectRatio="xMidYMid">
  <defs>
    <pattern id="lotus" x="0" y="0" width="120" height="120" patternUnits="userSpaceOnUse">
      <!-- 缠枝莲纹 -->
      <g fill="none" stroke="#1e3a8a" stroke-width="0.6" opacity="0.4">
        <path d="M 60 20 Q 80 40 60 60 Q 40 80 60 100 Q 80 80 60 60 Q 40 40 60 20"/>
        <circle cx="60" cy="60" r="6"/>
        <path d="M 60 54 L 60 66 M 54 60 L 66 60"/>
        <path d="M 20 60 Q 40 80 60 60"/>
        <path d="M 100 60 Q 80 80 60 60"/>
        <path d="M 0 0 Q 30 30 60 0"/>
        <path d="M 120 0 Q 90 30 60 0"/>
        <path d="M 0 120 Q 30 90 60 120"/>
        <path d="M 120 120 Q 90 90 60 120"/>
      </g>
    </pattern>
  </defs>
  <rect width="100%" height="100%" fill="url(#lotus)"/>
</svg>

<!-- 纸张纹理 -->
<div class="bg-grain"></div>

<!-- 浮动云纹 -->
<svg class="bg-cloud c1" viewBox="0 0 360 120" fill="none" stroke="#1e3a8a" stroke-width="1.2">
  <path d="M 30 60 Q 50 40 70 60 Q 90 80 110 60 Q 130 40 150 60 Q 170 80 190 60 Q 210 40 230 60 Q 250 80 270 60 Q 290 40 310 60 Q 330 80 350 60"/>
  <path d="M 30 80 Q 50 60 70 80 Q 90 100 110 80"/>
  <path d="M 150 40 Q 170 20 190 40 Q 210 60 230 40"/>
</svg>

<svg class="bg-cloud c2" viewBox="0 0 320 120" fill="none" stroke="#1e3a8a" stroke-width="1.2">
  <path d="M 20 60 Q 40 40 60 60 Q 80 80 100 60 Q 120 40 140 60 Q 160 80 180 60 Q 200 40 220 60 Q 240 80 260 60 Q 280 40 300 60"/>
  <path d="M 60 40 Q 80 20 100 40 Q 120 60 140 40"/>
  <path d="M 180 80 Q 200 60 220 80 Q 240 100 260 80"/>
</svg>

<svg class="bg-cloud c3" viewBox="0 0 280 120" fill="none" stroke="#1e3a8a" stroke-width="1.2">
  <path d="M 20 60 Q 40 40 60 60 Q 80 80 100 60 Q 120 40 140 60 Q 160 80 180 60 Q 200 40 220 60 Q 240 80 260 60"/>
  <path d="M 40 40 Q 60 20 80 40"/>
  <path d="M 160 80 Q 180 60 200 80"/>
</svg>

<!-- 头部 -->
<header class="porcelain-header">
  <!-- 缠枝花纹装饰 -->
  <svg class="header-ornament" viewBox="0 0 200 28" fill="none" stroke="#1e3a8a" stroke-width="1">
    <path d="M 0 14 Q 20 4 40 14 Q 60 24 80 14 Q 100 4 120 14 Q 140 24 160 14 Q 180 4 200 14"/>
    <circle cx="100" cy="14" r="3" fill="#c1272d" stroke="none"/>
    <circle cx="40" cy="14" r="2" fill="#1e3a8a" stroke="none"/>
    <circle cx="160" cy="14" r="2" fill="#1e3a8a" stroke="none"/>
    <path d="M 100 6 L 100 2 M 100 22 L 100 26"/>
  </svg>

  <div class="header-divider">
    <div class="line"></div>
    <div class="seal">青</div>
    <div class="line right"></div>
  </div>

  <h1 class="header-title">青花<span class="accent">瓷</span></h1>
  <p class="header-subtitle">舰娘 SD 资源整理台</p>
  <p class="header-poem">
    素胚勾勒出青花笔锋浓转淡<br>
    瓶身描绘的牡丹一如你初妆
    <span class="author">—— 方文山</span>
  </p>
</header>

<!-- 主区域 -->
<div class="porcelain-room">

  <!-- 左：配置面板 -->
  <section class="panel">
    <svg class="panel-border-top" viewBox="0 0 200 6" preserveAspectRatio="none">
      <path d="M 0 3 Q 25 0 50 3 Q 75 6 100 3 Q 125 0 150 3 Q 175 6 200 3" fill="none" stroke="#1e3a8a" stroke-width="1"/>
    </svg>

    <div class="panel-title">
      <span class="index">壹</span>
      配置
    </div>

    <div class="field-group">
      <label class="field-label" for="src"><span class="num">01</span>源目录</label>
      <input type="text" class="input-field" id="src" value="__DEFAULT_SRC__"
             placeholder="含 TextAsset/ 和 Texture2D/ 的目录">
    </div>

    <div class="field-group">
      <label class="field-label" for="out"><span class="num">02</span>输出目录</label>
      <input type="text" class="input-field" id="out" value="__DEFAULT_OUT__"
             placeholder="整理后的输出位置">
    </div>

    <div class="options-grid">
      <label class="toggle">
        <input type="checkbox" id="dry_run" checked>
        <span class="lever"></span>
        <span class="toggle-label">预览模式</span>
      </label>
      <label class="toggle">
        <input type="checkbox" id="clean">
        <span class="lever"></span>
        <span class="toggle-label">清空重建</span>
      </label>
      <label class="toggle">
        <input type="checkbox" id="include_lr">
        <span class="lever"></span>
        <span class="toggle-label">含半身立绘</span>
      </label>
    </div>

    <div class="btn-row">
      <button class="btn btn-secondary" id="scan-btn" onclick="doScan()">
        <span class="btn-text">扫描预览</span>
      </button>
      <button class="btn btn-primary" id="organize-btn" onclick="doOrganize()">
        <span class="btn-text">执行整理</span>
      </button>
    </div>
    <div class="progress-bar" id="progress" style="display:none;">
      <div class="progress-fill" style="width:0%"></div>
    </div>

    <svg class="panel-border-bottom" viewBox="0 0 200 6" preserveAspectRatio="none">
      <path d="M 0 3 Q 25 0 50 3 Q 75 6 100 3 Q 125 0 150 3 Q 175 6 200 3" fill="none" stroke="#1e3a8a" stroke-width="1"/>
    </svg>
  </section>

  <!-- 右：结果面板 -->
  <section class="panel result-area">
    <svg class="panel-border-top" viewBox="0 0 200 6" preserveAspectRatio="none">
      <path d="M 0 3 Q 25 0 50 3 Q 75 6 100 3 Q 125 0 150 3 Q 175 6 200 3" fill="none" stroke="#1e3a8a" stroke-width="1"/>
    </svg>

    <div class="panel-title">
      <span class="index">贰</span>
      资源清单
    </div>

    <div id="result-placeholder" class="placeholder">
      <svg class="vase" viewBox="0 0 72 80" fill="none" stroke="#1e3a8a" stroke-width="1.5">
        <!-- 青花瓷瓶轮廓 -->
        <path d="M 28 8 Q 36 4 44 8 L 44 14 Q 50 18 52 26 Q 54 38 50 50 Q 46 62 38 70 Q 36 74 36 76 L 36 78 L 36 78 Q 36 78 36 78 L 36 76 Q 36 74 34 70 Q 26 62 22 50 Q 18 38 20 26 Q 22 18 28 14 Z"/>
        <!-- 瓶身花纹 -->
        <circle cx="36" cy="36" r="6" fill="none"/>
        <path d="M 30 36 L 42 36 M 36 30 L 36 42"/>
        <path d="M 28 24 Q 36 20 44 24"/>
        <path d="M 26 50 Q 36 46 46 50"/>
        <path d="M 32 14 L 40 14"/>
      </svg>
      <p>静候调遣 — 点击<span class="hint">「扫描预览」</span>开启资源清点</p>
    </div>

    <div id="result-content" style="display:none;">
      <div class="stats-grid" id="stats-grid"></div>
      <div class="search-bar">
        <svg class="search-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8"/>
          <line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        <input type="text" class="input-field" id="search" placeholder="搜索舰娘名（支持拼音 / 中文）" oninput="filterTree()">
        <span class="kbd-hint">Ctrl K</span>
      </div>
      <div class="tree-container" id="tree-view"></div>
    </div>

    <svg class="panel-border-bottom" viewBox="0 0 200 6" preserveAspectRatio="none">
      <path d="M 0 3 Q 25 0 50 3 Q 75 6 100 3 Q 125 0 150 3 Q 175 6 200 3" fill="none" stroke="#1e3a8a" stroke-width="1"/>
    </svg>
  </section>
</div>

<!-- 指南 -->
<div class="guide-section">
  <div class="guide-card">
    <h3>使用说明</h3>
    <div class="guide-steps">
      <div class="guide-step">
        <span class="step-num">壹</span>
        <div class="step-text">将解包的 SD 资源放入 <code>sd/TextAsset/</code> 与 <code>sd/Texture2D/</code> 目录</div>
      </div>
      <div class="guide-step">
        <span class="step-num">贰</span>
        <div class="step-text">点击 <strong>扫描预览</strong> 查看自动分类结果（舰娘 → 皮肤 二级归档）</div>
      </div>
      <div class="guide-step">
        <span class="step-num">叁</span>
        <div class="step-text">取消 <strong>预览模式</strong> 勾选，点击 <strong>执行整理</strong> 生成资源目录</div>
      </div>
      <div class="guide-step">
        <span class="step-num">肆</span>
        <div class="step-text">将 <code>organized/</code> 目录整体复制到手机 <code>Download/BLYY/blhx_sd/</code></div>
      </div>
    </div>
  </div>
</div>

<!-- 页脚 -->
<footer class="porcelain-footer">
  <svg class="crest" viewBox="0 0 40 40" fill="none" stroke="#1e3a8a" stroke-width="1.2">
    <!-- 青花瓷印章式徽章 -->
    <rect x="4" y="4" width="32" height="32" rx="2" stroke-width="1.5"/>
    <rect x="8" y="8" width="24" height="24" rx="1"/>
    <circle cx="20" cy="20" r="8"/>
    <path d="M 20 12 L 20 28 M 12 20 L 28 20"/>
    <circle cx="20" cy="20" r="2" fill="#c1272d" stroke="none"/>
  </svg>
  <p>青花瓷 · 碧蓝航线舰娘资源整理 <span class="seal-mini">蓝</span> 谨制</p>
</footer>

<div class="toast-container" id="toast-container"></div>

<script>
// ── 状态 ──
let scanResult = null;

// ── API 调用 ──
async function fetchJSON(url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return res.json();
}

function getConfig() {
  return {
    src: document.getElementById('src').value.trim(),
    out: document.getElementById('out').value.trim(),
    dry_run: document.getElementById('dry_run').checked,
    clean: document.getElementById('clean').checked,
    include_lr: document.getElementById('include_lr').checked,
  };
}

// ── 按钮状态 ──
function setBtnLoading(btnId, loading) {
  const btn = document.getElementById(btnId);
  const text = btn.querySelector('.btn-text');
  if (loading) {
    btn.disabled = true;
    btn.dataset.originalText = text.textContent;
    text.innerHTML = '<span class="spinner"></span>';
  } else {
    btn.disabled = false;
    if (btn.dataset.originalText) text.textContent = btn.dataset.originalText;
  }
}

function showProgress(percent) {
  const bar = document.getElementById('progress');
  bar.style.display = 'block';
  bar.querySelector('.progress-fill').style.width = percent + '%';
}

function hideProgress() {
  setTimeout(() => {
    document.getElementById('progress').style.display = 'none';
  }, 600);
}

// ── Toast 通知 ──
function toast(message, type = 'info', duration = 4000) {
  const container = document.getElementById('toast-container');
  const el = document.createElement('div');
  el.className = `toast ${type}`;

  const icons = {
    success: '<svg class="toast-icon" viewBox="0 0 24 24" fill="none" stroke="#2d6a4f" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>',
    error: '<svg class="toast-icon" viewBox="0 0 24 24" fill="none" stroke="#c1272d" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>',
    warning: '<svg class="toast-icon" viewBox="0 0 24 24" fill="none" stroke="#b8860b" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
    info: '<svg class="toast-icon" viewBox="0 0 24 24" fill="none" stroke="#1e3a8a" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>',
  };

  el.innerHTML = (icons[type] || icons.info) + '<span>' + message + '</span>';
  container.appendChild(el);

  setTimeout(() => {
    el.classList.add('removing');
    setTimeout(() => el.remove(), 300);
  }, duration);
}

// ── 扫描预览 ──
async function doScan() {
  const cfg = getConfig();
  setBtnLoading('scan-btn', true);
  showProgress(30);

  try {
    const res = await fetchJSON('/api/scan', {
      src: cfg.src,
      include_lr: cfg.include_lr,
    });
    showProgress(100);

    if (res.ok) {
      scanResult = res.data;
      renderResult(scanResult);
      toast(`清点完毕 — 共发现 ${scanResult.ship_count} 位舰娘，${scanResult.skin_count} 套皮肤`, 'success');
    } else {
      toast(res.error || '扫描失败', 'error');
    }
  } catch (e) {
    toast('网络错误：' + e.message, 'error');
  } finally {
    setBtnLoading('scan-btn', false);
    hideProgress();
  }
}

// ── 执行整理 ──
async function doOrganize() {
  const cfg = getConfig();
  if (!cfg.dry_run && !confirm('确认执行整理？这将写入文件到输出目录。')) return;

  setBtnLoading('organize-btn', true);
  showProgress(40);

  try {
    const res = await fetchJSON('/api/organize', cfg);
    showProgress(100);

    if (res.ok) {
      scanResult = res.data;
      renderResult(scanResult);
      const s = res.data.stats;
      toast(`整理完成 — 复制 ${s.copied} 个文件，跳过 ${s.skipped} 个`, 'success', 5000);
    } else {
      toast(res.error || '整理失败', 'error');
    }
  } catch (e) {
    toast('网络错误：' + e.message, 'error');
  } finally {
    setBtnLoading('organize-btn', false);
    hideProgress();
  }
}

// ── 渲染结果 ──
function renderResult(data) {
  document.getElementById('result-placeholder').style.display = 'none';
  document.getElementById('result-content').style.display = 'block';

  // 统计卡片
  const stats = [
    { value: data.ship_count, label: '舰娘' },
    { value: data.skin_count, label: '皮肤' },
    { value: data.stats ? data.stats.copied : '—', label: '已复制' },
    { value: data.stats ? data.stats.skipped : '—', label: '已跳过' },
  ];
  document.getElementById('stats-grid').innerHTML = stats.map(s => `
    <div class="stat-card">
      <div class="stat-value">${s.value}</div>
      <div class="stat-label">${s.label}</div>
    </div>
  `).join('');

  renderTree(data.ships);
}

function renderTree(ships) {
  const entries = Object.entries(ships).sort((a, b) => a[0].localeCompare(b[0]));
  const html = entries.map(([shipName, shipData]) => {
    const skins = shipData.skins;
    const skinCount = Object.keys(skins).length;
    const skinHtml = Object.entries(skins).sort((a, b) => a[0].localeCompare(b[0])).map(([skinName, files]) => {
      const fileNodes = [];
      if (files.skel) fileNodes.push(fileNode(files.skel, 'skel'));
      if (files.atlas) {
        fileNodes.push(fileNode(files.atlas, 'atlas'));
      } else if (files.skel) {
        const atlasName = files.skel.replace(/\.skel$/i, '.atlas');
        fileNodes.push(`<div class="tree-row"><span class="tree-icon warning">⚠</span><span>${atlasName}</span><span class="tree-note">— 由默认皮肤改写</span></div>`);
      }
      if (files.png) fileNodes.push(fileNode(files.png, 'png'));
      (files.extra_pngs || []).forEach(p => fileNodes.push(fileNode(p, 'png')));

      return `<div class="tree-node" data-expanded="true">
        <div class="tree-row expandable" onclick="toggleNode(this)">
          <span class="tree-arrow">▼</span>
          <span class="tree-icon skin">◆</span>
          <span class="tree-skin-name">${skinName}</span>
          <span class="tree-badge">${fileNodes.length} 文件</span>
        </div>
        <div class="tree-children">${fileNodes.join('')}</div>
      </div>`;
    }).join('');

    return `<div class="tree-node ship-node" data-expanded="true" data-ship="${shipName}">
      <div class="tree-row expandable" onclick="toggleNode(this)">
        <span class="tree-arrow">▼</span>
        <span class="tree-icon ship">⚓</span>
        <span class="tree-ship-name">${shipName}</span>
        <span class="tree-badge">${skinCount} 皮肤</span>
      </div>
      <div class="tree-children">${skinHtml}</div>
    </div>`;
  }).join('');

  document.getElementById('tree-view').innerHTML = html;
}

function fileNode(name, type) {
  const icons = { skel: '⬡', atlas: '▣', png: '▦' };
  return `<div class="tree-row"><span class="tree-icon ${type}">${icons[type] || '○'}</span><span>${name}</span></div>`;
}

function toggleNode(row) {
  const node = row.parentElement;
  node.classList.toggle('collapsed');
}

// ── 搜索过滤 ──
function filterTree() {
  const q = document.getElementById('search').value.toLowerCase().trim();
  document.querySelectorAll('.ship-node').forEach(node => {
    const name = node.dataset.ship.toLowerCase();
    node.style.display = (!q || name.includes(q)) ? '' : 'none';
  });
}

// ── 键盘快捷键 ──
document.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
    e.preventDefault();
    const search = document.getElementById('search');
    if (search) {
      search.focus();
      search.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }
});
</script>
</body>
</html>'''


# ──────────────────────────────────────────────────────────────
# 辅助函数
# ──────────────────────────────────────────────────────────────

def groups_to_json(groups: dict) -> dict:
    """将 groups（含 Path 对象）转为 JSON 可序列化格式。"""
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
                'skel': files['skel'].name,
                'atlas': files['atlas'].name if files['atlas'] else None,
                'png': files['png'].name if files['png'] else None,
                'extra_pngs': [p.name for p in files.get('extra_pngs', [])],
            }
        if skin_entries:
            ships[base_name] = {'skins': skin_entries}
    return {'ships': ships, 'ship_count': len(ships), 'skin_count': skin_total}


def resolve_path(path_str: str) -> Path:
    """解析路径（相对路径基于脚本目录）。"""
    p = Path(path_str)
    if not p.is_absolute():
        p = SCRIPT_DIR / p
    return p.resolve()


# ──────────────────────────────────────────────────────────────
# HTTP 请求处理器
# ──────────────────────────────────────────────────────────────

class GuiHandler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        """静默 HTTP 日志。"""
        pass

    def do_GET(self):
        if self.path in ('/', '/index.html'):
            self._serve_html()
        else:
            self.send_error(404)

    def do_POST(self):
        if self.path == '/api/scan':
            self._handle_scan()
        elif self.path == '/api/organize':
            self._handle_organize()
        else:
            self.send_error(404)

    def _serve_html(self):
        html = HTML_CONTENT.replace('__DEFAULT_SRC__', str(DEFAULT_SRC))
        html = html.replace('__DEFAULT_OUT__', str(DEFAULT_OUT))
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.end_headers()
        self.wfile.write(html.encode('utf-8'))

    def _read_json(self) -> dict:
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length).decode('utf-8')
        return json.loads(body) if body else {}

    def _send_json(self, data: dict):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode('utf-8'))

    def _send_error(self, message: str):
        self._send_json({'ok': False, 'error': message})

    def _handle_scan(self):
        try:
            params = self._read_json()
            src = resolve_path(params.get('src', str(DEFAULT_SRC)))
            include_lr = params.get('include_lr', False)

            if not src.exists():
                self._send_error(f'源目录不存在: {src}')
                return

            groups = scan_source(src, include_lr=include_lr)
            result = groups_to_json(groups)
            self._send_json({'ok': True, 'data': result})
        except Exception as e:
            traceback.print_exc()
            self._send_error(f'扫描失败: {e}')

    def _handle_organize(self):
        try:
            params = self._read_json()
            src = resolve_path(params.get('src', str(DEFAULT_SRC)))
            out = resolve_path(params.get('out', str(DEFAULT_OUT)))
            dry_run = params.get('dry_run', False)
            clean = params.get('clean', False)
            include_lr = params.get('include_lr', False)

            if not src.exists():
                self._send_error(f'源目录不存在: {src}')
                return

            groups = scan_source(src, include_lr=include_lr)
            manifest = build_manifest(groups)
            stats = copy_groups(groups, out, dry_run, clean)
            if not dry_run:
                write_manifest(manifest, out, dry_run)

            result = groups_to_json(groups)
            result['stats'] = stats
            result['manifest'] = manifest
            self._send_json({'ok': True, 'data': result})
        except Exception as e:
            traceback.print_exc()
            self._send_error(f'整理失败: {e}')


# ──────────────────────────────────────────────────────────────
# 主入口
# ──────────────────────────────────────────────────────────────

def find_free_port() -> int:
    """自动查找可用端口。"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def main():
    port = find_free_port()
    url = f'http://127.0.0.1:{port}'

    server = ThreadingHTTPServer(('127.0.0.1', port), GuiHandler)

    # 青花瓷风格的开屏横幅
    print()
    print('  ╔══════════════════════════════════════════════════╗')
    print('  ║                                                  ║')
    print('  ║   青花瓷 · SD 资源整理台                          ║')
    print('  ║   Blue & White Porcelain · Spine Asset Arsenal    ║')
    print('  ║                                                  ║')
    print('  ╠══════════════════════════════════════════════════╣')
    print(f'  ║   地址 : {url:<38s}    ║')
    print(f'  ║   源   : {str(DEFAULT_SRC):<38s}    ║')
    print(f'  ║   出   : {str(DEFAULT_OUT):<38s}    ║')
    print('  ║                                                  ║')
    print('  ║   按 Ctrl+C 关闭服务器                            ║')
    print('  ╚══════════════════════════════════════════════════╝')
    print()

    # 延迟打开浏览器（给服务器一点启动时间）
    import threading
    threading.Timer(0.5, lambda: webbrowser.open(url)).start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\n  窑火渐熄...')
        server.shutdown()
        print('  已关闭\n')


if __name__ == '__main__':
    main()
