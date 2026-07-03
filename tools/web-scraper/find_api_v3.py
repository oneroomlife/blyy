"""分析 gamekee 主 JS bundle 查找 API 端点"""
import sys
import io
import re
import requests

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
}

# 获取主 JS bundle
js_urls = [
    "https://cdnstatic.gamekee.com/wiki/spa/apps/web/client/dist/js/main.d6071bf3.js",
    "https://cdnstatic.gamekee.com/wiki/spa/apps/web/client/dist/js/8333.e7c35175.js",
]

for js_url in js_urls:
    try:
        resp = requests.get(js_url, headers=headers, timeout=30)
        print(f"\n[{resp.status_code}] {js_url} (长度: {len(resp.text)})")
        if resp.status_code == 200:
            text = resp.text
            # 查找 API 路径模式
            api_paths = re.findall(r'["\'](/api/[^"\']+)["\']', text)
            print(f"  /api/ 路径: {api_paths[:20]}")
            
            # 查找 gamekee API
            gamekee_api = re.findall(r'["\'](?:https?:)?//api\.gamekee\.com([^"\']+)["\']', text)
            print(f"  api.gamekee.com 路径: {gamekee_api[:20]}")
            
            # 查找 voice 相关
            voice_patterns = re.findall(r'["\']([^"\']*voice[^"\']*)["\']', text, re.IGNORECASE)
            print(f"  voice 相关: {voice_patterns[:10]}")
            
            # 查找 dub 相关
            dub_patterns = re.findall(r'["\']([^"\']*dub[^"\']*)["\']', text, re.IGNORECASE)
            print(f"  dub 相关: {dub_patterns[:10]}")
            
            # 查找 content/entry 相关 API
            content_patterns = re.findall(r'["\']([^"\']*(?:content|entry|detail|wiki)[^"\']*)["\']', text, re.IGNORECASE)
            print(f"  content/entry 相关: {content_patterns[:15]}")
            
            # 查找 axios/fetch 调用模式
            axios_patterns = re.findall(r'(?:axios|fetch|request|get|post)\.\w+\s*\(\s*["\']([^"\']+)["\']', text)
            print(f"  axios/fetch 调用: {axios_patterns[:15]}")
            
            # 查找 baseURL
            baseurl_patterns = re.findall(r'baseURL\s*[:=]\s*["\']([^"\']+)["\']', text)
            print(f"  baseURL: {baseurl_patterns[:5]}")
    except Exception as e:
        print(f"[ERR] {js_url}: {e}")

# 尝试直接访问 gamekee 的内容 API
print("\n=== 尝试 gamekee 内容 API ===")
# gamekee 可能使用数字 ID 作为内容标识
# 59934 是学生的 entry ID
# 尝试不同的 API 模式
content_apis = [
    "https://api.gamekee.com/v1/mobile/entry/59934",
    "https://api.gamekee.com/v1/mobile/content/59934",
    "https://api.gamekee.com/v1/entry/59934/voice",
    "https://api.gamekee.com/v1/entry/59934?tab=2",
    "https://api.gamekee.com/v1/wiki/59934",
    "https://api.gamekee.com/v1/wiki/59934/voice",
    "https://api.gamekee.com/v1/wiki/59934/gallery",
]

for url in content_apis:
    try:
        resp = requests.get(url, headers={**headers, "Accept": "application/json"}, timeout=10)
        content_type = resp.headers.get("Content-Type", "")
        print(f"[{resp.status_code}] {url} (Content-Type: {content_type})")
        if resp.status_code == 200 and "json" in content_type.lower():
            print(f"  JSON 内容: {resp.text[:500]}")
    except Exception as e:
        print(f"[ERR] {url}: {e}")
