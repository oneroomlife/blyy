import requests, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

# 测试不同的 URL 格式
urls = [
    # 1. 原始完整 URL（清理后，带 webp 转换）
    "https://cdnimg-v2.gamekee.com/wiki2.0/images/w_404/h_456/829/43637/2025/6/23/714010.png?image_process=resize,mid,w_300,h_300&eo-img.resize=w/300&x-image-process=image/format,webp/ignore-error,1&eo-img.format=webp",
    # 2. 去掉所有查询参数（原始 PNG）
    "https://cdnimg-v2.gamekee.com/wiki2.0/images/w_404/h_456/829/43637/2025/6/23/714010.png",
    # 3. 只保留 webp 格式转换
    "https://cdnimg-v2.gamekee.com/wiki2.0/images/w_404/h_456/829/43637/2025/6/23/714010.png?x-image-process=image/format,webp/ignore-error,1",
    # 4. 只保留 resize 参数
    "https://cdnimg-v2.gamekee.com/wiki2.0/images/w_404/h_456/829/43637/2025/6/23/714010.png?image_process=resize,mid,w_300,h_300",
    # 5. 带 Referer 头
    "https://cdnimg-v2.gamekee.com/wiki2.0/images/w_404/h_456/829/43637/2025/6/23/714010.png?x-image-process=image/format,webp/ignore-error,1",
]

headers_list = [
    # 0: 默认（无 Referer）
    {"User-Agent": "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36"},
    # 1: 带 gamekee Referer
    {"User-Agent": "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36", "Referer": "https://www.gamekee.com/"},
    # 2: 带 biligame Referer
    {"User-Agent": "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36", "Referer": "https://wiki.biligame.com/"},
]

for i, url in enumerate(urls[:4]):
    for j, headers in enumerate(headers_list):
        try:
            r = requests.head(url, headers=headers, timeout=10, allow_redirects=True)
            print(f"[{i},{j}] Status={r.status_code} Content-Type={r.headers.get('Content-Type','?')} URL={url[:80]}...")
        except Exception as e:
            print(f"[{i},{j}] ERROR: {e} URL={url[:80]}...")

# 测试用户提供的示例 URL（妃咲泳装）
print("\n--- 用户示例 URL ---")
example_urls = [
    # 完整原始 URL（带重复参数）
    "https://cdnimg-v2.gamekee.com/wiki2.0/pro/829/images/w_404/h_456/492704/2026/5/24/93920569mqs4z9g5.png?x-image-process=image/resize,m_lfit,h_300,w_300/ignore-error,1&image_process=resize,mid,w_300,h_300&eo-img.resize=w/300&x-image-process=image/format,webp/ignore-error,1&eo-img.format=webp",
    # 去掉所有参数
    "https://cdnimg-v2.gamekee.com/wiki2.0/pro/829/images/w_404/h_456/492704/2026/5/24/93920569mqs4z9g5.png",
    # 只保留 webp
    "https://cdnimg-v2.gamekee.com/wiki2.0/pro/829/images/w_404/h_456/492704/2026/5/24/93920569mqs4z9g5.png?x-image-process=image/format,webp/ignore-error,1",
]
for i, url in enumerate(example_urls):
    for j, headers in enumerate(headers_list):
        try:
            r = requests.head(url, headers=headers, timeout=10, allow_redirects=True)
            print(f"[ex{i},{j}] Status={r.status_code} Content-Type={r.headers.get('Content-Type','?')} URL={url[:80]}...")
        except Exception as e:
            print(f"[ex{i},{j}] ERROR: {e} URL={url[:80]}...")
