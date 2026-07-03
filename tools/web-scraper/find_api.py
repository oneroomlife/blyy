"""检查语音页面是否有嵌入式 API 数据或 JSON 数据"""
import sys
import io
import re
import json
from pathlib import Path
from bs4 import BeautifulSoup

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

# 检查原始（非JS）语音页面
html_raw = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\student_voice.html").read_text(encoding="utf-8")
print(f"原始语音页 HTML 长度: {len(html_raw)}")

# 1. 查找 script 中的 JSON 数据
print("\n=== 查找 script 中的 JSON/API 数据 ===")
soup = BeautifulSoup(html_raw, "html.parser")
scripts = soup.find_all("script")
print(f"script 标签数: {len(scripts)}")

for i, script in enumerate(scripts):
    text = script.string or ""
    if not text:
        continue
    # 查找可能的 JSON 数据
    if any(keyword in text.lower() for keyword in ["voice", "audio", "dub", "ogg", "mp3", "__initial", "__nuxt", "window.__", "api"]):
        print(f"\n--- script {i} (含关键词, 前2000字符) ---")
        print(text[:2000])

# 2. 查找 window.__ 数据
print("\n=== 查找 window.__ 数据 ===")
window_data = re.findall(r'window\.__\w+\s*=\s*({.*?});', html_raw, re.DOTALL)
print(f"window.__ 数据块: {len(window_data)}")
for j, data in enumerate(window_data[:3]):
    print(f"\n--- window.__ 数据 {j} (前1000字符) ---")
    print(data[:1000])

# 3. 查找 API 端点
print("\n=== 查找 API 端点 ===")
api_patterns = re.findall(r'(?:api|fetch|axios|request)[^"]*["\']([^"\']+)["\']', html_raw, re.IGNORECASE)
print(f"API 端点: {api_patterns[:20]}")

url_patterns = re.findall(r'["\']/(?:api|v\d)/([^"\']+)["\']', html_raw)
print(f"URL 模式: {url_patterns[:20]}")

# 4. 查找 data-v 属性中的数据
print("\n=== 查找 Vue 数据 ===")
vue_data = re.findall(r'data-v-\w+="([^"]+)"', html_raw)
print(f"Vue data-v 属性数: {len(vue_data)}")

# 5. 检查 JS 渲染后的页面中的 API 调用
print("\n=== 检查 JS 渲染后页面的 API 调用 ===")
html_js = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\student_voice_js.html").read_text(encoding="utf-8")

# 查找所有外部资源 URL
urls = re.findall(r'https?://[^\s"\'<>]+', html_js)
unique_domains = set()
for url in urls:
    if "gamekee" in url:
        # 提取路径
        match = re.match(r'https?://[^/]+(/[^?"]+)', url)
        if match:
            unique_domains.add(match.group(1).split("/")[1] if "/" in match.group(1) else match.group(1))

print(f"gamekee 路径前缀: {unique_domains}")

# 6. 查找 script src
print("\n=== script src ===")
script_srcs = soup.select("script[src]")
for s in script_srcs[:10]:
    print(f"  {s.get('src', '')[:120]}")

# 7. 检查是否有内联的语音数据
print("\n=== 检查内联语音数据 ===")
# 查找包含 ogg 的 script
ogg_in_script = re.findall(r'["\']([^"\']*\.ogg)["\']', html_raw)
print(f"原始 HTML 中 ogg 链接: {len(ogg_in_script)}")

ogg_in_js = re.findall(r'["\']([^"\']*\.ogg)["\']', html_js)
print(f"JS 渲染后 ogg 链接: {len(ogg_in_js)}")
if ogg_in_js:
    print("前5个:")
    for url in ogg_in_js[:5]:
        print(f"  {url}")

# 8. 检查 59934 这个 ID 在页面中的引用
print("\n=== 检查 59934 ID 引用 ===")
id_refs = re.findall(r'59994|59934', html_raw)
print(f"59934 引用数: {len(id_refs)}")

# 9. 查找 gamekee API 模式
print("\n=== 查找 gamekee API 模式 ===")
api_calls = re.findall(r'["\'](?:https?:)?//[^"\']*gamekee[^"\']*api[^"\']*["\']', html_raw, re.IGNORECASE)
print(f"gamekee API 调用: {api_calls[:10]}")

# 查找 wiki/content 相关 URL
wiki_urls = re.findall(r'["\'](?:https?:)?//[^"\']*gamekee[^"\']*(?:wiki|content|entry|detail)[^"\']*["\']', html_raw, re.IGNORECASE)
print(f"wiki/content URL: {wiki_urls[:10]}")
