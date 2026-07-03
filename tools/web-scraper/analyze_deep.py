"""深入分析语音条目完整结构和立绘页面"""
import sys
import io
import re
from pathlib import Path
from bs4 import BeautifulSoup

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

# ============ 分析语音页面 ============
html = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\student_voice_js.html").read_text(encoding="utf-8")
soup = BeautifulSoup(html, "html.parser")

print("=" * 60)
print("语音页面深入分析")
print("=" * 60)

# 1. 分析 list-group-item 完整结构
list_items = soup.select(".list-group-item")
print(f"\nlist-group-item 总数: {len(list_items)}")

if list_items:
    print("\n--- 第一个 list-group-item 完整结构 ---")
    item = list_items[0]
    print(item.prettify()[:3000])

    print("\n--- 第三个 list-group-item (可能有不同结构) ---")
    if len(list_items) > 2:
        print(list_items[2].prettify()[:3000])

# 2. 分析 list-group 的 id
print("\n=== list-group id 分析 ===")
list_groups = soup.select(".list-group")
for lg in list_groups:
    print(f"list-group id={lg.get('id')}, items={len(lg.select('.list-group-item'))}")

# 3. 分析 dub-item 结构
print("\n=== dub-item 结构 ===")
dub_items = soup.select(".dub-item")
print(f"dub-item 总数: {len(dub_items)}")
if dub_items:
    print(dub_items[0].prettify()[:1500])

# 4. 查找语音文本内容
print("\n=== 查找语音文本 ===")
# 查找 item-body 或类似的内容区域
item_bodies = soup.select(".item-body, .item-content, .voice-text, .text-content, .item-text")
print(f"item-body/content 类: {len(item_bodies)}")

# 查找所有包含文本的元素
print("\n--- 第一个 list-group-item 的所有文本 ---")
if list_items:
    item = list_items[0]
    for elem in item.find_all(True):
        text = elem.get_text(strip=True)
        if text and len(text) > 2:
            classes = elem.get("class", [])
            print(f"  <{elem.name} class='{classes}'>: {text[:100]}")

# 5. 分析 voice-tab 切换逻辑
print("\n=== voice-tab 切换逻辑 ===")
voice_tabs = soup.select(".voice-tab-item")
for tab in voice_tabs:
    print(f"tab: text='{tab.get_text(strip=True)}', data-report-key='{tab.get('data-report-key')}'")

# 6. 查找所有 list-group (每个 tab 对应一个)
print("\n=== 所有 list-group ===")
all_list_groups = soup.select(".list-group")
for lg in all_list_groups:
    lg_id = lg.get("id", "")
    items = lg.select(".list-group-item")
    print(f"id={lg_id}, items={len(items)}")
    if items:
        # 打印第一个 item 的标题
        title = items[0].select_one(".item-header-title")
        if title:
            print(f"  第一个标题: {title.get_text(strip=True)}")

# ============ 分析立绘页面 ============
print("\n" + "=" * 60)
print("立绘页面深入分析")
print("=" * 60)

gallery_html = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\student_gallery_js.html").read_text(encoding="utf-8")
gallery_soup = BeautifulSoup(gallery_html, "html.parser")

# 1. 查找所有 tab
print("\n=== 立绘页面 tab ===")
tabs = gallery_soup.select(".tab-box .tab, .tab-box .normal-tab")
for tab in tabs:
    print(f"tab: text='{tab.get_text(strip=True)}', class='{tab.get('class')}', data-report-key='{tab.get('data-report-key')}'")

# 2. 查找所有 header-container (每个 tab 对应一个)
print("\n=== header-container ===")
headers = gallery_soup.select(".header-container")
for h in headers:
    h_id = h.get("id", "")
    images = h.select("img")
    print(f"id={h_id}, images={len(images)}")
    # 打印前3个图片 URL
    for img in images[:3]:
        src = img.get("src", "") or img.get("data-src", "")
        if src and not src.startswith("data:"):
            print(f"  img: {src[:120]}")

# 3. 查找 image-list
print("\n=== image-list ===")
image_lists = gallery_soup.select(".image-list")
print(f"image-list 数量: {len(image_lists)}")
for il in image_lists[:3]:
    images = il.select("img")
    print(f"  image-list images: {len(images)}")
    for img in images[:2]:
        src = img.get("src", "") or img.get("data-src", "")
        if src and not src.startswith("data:"):
            print(f"    {src[:120]}")

# 4. 查找所有非 base64 图片
print("\n=== 所有非 base64 图片 ===")
all_imgs = gallery_soup.find_all("img")
real_imgs = []
for img in all_imgs:
    src = img.get("src", "") or img.get("data-src", "")
    if src and not src.startswith("data:"):
        real_imgs.append(src)
print(f"真实图片数: {len(real_imgs)}")
for src in real_imgs[:10]:
    print(f"  {src[:120]}")

# 5. 查找 video 相关
print("\n=== video 相关 ===")
videos = gallery_soup.select("video, .video-group, .video-list-item")
print(f"video 元素: {len(videos)}")
for v in videos[:3]:
    print(v.prettify()[:500])

# 6. 查找 sdj-text (图片描述)
print("\n=== sdj-text (图片描述) ===")
sdj_texts = gallery_soup.select(".sdj-text")
print(f"sdj-text 数量: {len(sdj_texts)}")
for st in sdj_texts[:5]:
    print(f"  {st.get_text(strip=True)[:100]}")
