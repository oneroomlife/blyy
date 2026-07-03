"""尝试查找 gamekee API 端点"""
import sys
import io
import requests
import json
import re

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://www.gamekee.com/ba/tj/59934.html",
    "Origin": "https://www.gamekee.com",
}

# 尝试常见的 gamekee API 端点
api_urls = [
    "https://api.gamekee.com/v1/content/59934",
    "https://api.gamekee.com/v1/entry/59934",
    "https://www.gamekee.com/api/v1/content/59934",
    "https://www.gamekee.com/api/v1/entry/59934",
    "https://api.gamekee.com/content/59934",
    "https://api.gamekee.com/entry/59934",
    "https://www.gamekee.com/ba/tj/59934.json",
    "https://www.gamekee.com/api/ba/tj/59934",
    "https://api.gamekee.com/v1/wiki/entry/59934",
    "https://api.gamekee.com/v1/wiki/content/59934",
    "https://api.gamekee.com/wiki/entry/59934",
    "https://api.gamekee.com/wiki/content/59934",
]

for url in api_urls:
    try:
        resp = requests.get(url, headers=headers, timeout=10)
        print(f"[{resp.status_code}] {url}")
        if resp.status_code == 200:
            content = resp.text[:500]
            print(f"  内容: {content}")
            print()
    except Exception as e:
        print(f"[ERR] {url}: {e}")

# 尝试获取 JS bundle 来分析 API 调用
print("\n=== 获取 JS bundle 分析 API ===")
js_urls = [
    "https://cdnstatic.gamekee.com/wiki/spa/apps/web/client/dist/js/runtime~main.2e80bf46.js",
    "https://cdnstatic.gamekee.com/wiki/spa/apps/web/client/dist/js/main.js",
]

for js_url in js_urls:
    try:
        resp = requests.get(js_url, headers=headers, timeout=15)
        print(f"[{resp.status_code}] {js_url} (长度: {len(resp.text)})")
        if resp.status_code == 200:
            # 查找 API 模式
            api_patterns = re.findall(r'["\'](?:/api/|https?://api\.gamekee)[^"\']+["\']', resp.text)
            print(f"  API 模式: {api_patterns[:10]}")
            
            # 查找 fetch/axios 调用
            fetch_patterns = re.findall(r'(?:fetch|axios|request)\(["\']([^"\']+)["\']', resp.text)
            print(f"  fetch 调用: {fetch_patterns[:10]}")
    except Exception as e:
        print(f"[ERR] {js_url}: {e}")

# 尝试查找 chunk JS 文件
print("\n=== 查找 chunk JS 文件 ===")
try:
    resp = requests.get("https://www.gamekee.com/ba/tj/59934.html", headers=headers, timeout=15)
    if resp.status_code == 200:
        # 查找所有 JS 文件引用
        js_files = re.findall(r'src=["\']([^"\']*\.js)["\']', resp.text)
        print(f"JS 文件: {js_files}")
        
        # 查找 chunk 文件
        chunk_files = re.findall(r'chunk[^"\']*\.js', resp.text)
        print(f"chunk 文件: {chunk_files}")
except Exception as e:
    print(f"[ERR] {e}")
