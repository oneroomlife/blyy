"""更精细地分析学生列表 HTML 结构，提取每个学生的头像"""
import sys
import re
import json
from pathlib import Path

sys.path.insert(0, r"D:\Python\python\Lib\site-packages")
from bs4 import BeautifulSoup

HTML_PATH = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\students_list.html")

html = HTML_PATH.read_text(encoding="utf-8", errors="ignore")
soup = BeautifulSoup(html, "html.parser")

# 找到所有 /ba/tj/ 链接
all_links = soup.find_all("a", href=True)
tj_links = [a for a in all_links if "/ba/tj/" in a.get("href", "")]
print(f"/ba/tj/ 链接数: {len(tj_links)}")

# 查看前 3 个链接的完整结构
print("\n=== 前 3 个链接的详细结构 ===")
for i, a in enumerate(tj_links[:3]):
    print(f"\n--- 链接 {i}: {a.get('href')} ---")
    print(f"a 本身: {a}")
    # 检查 a 标签内部是否有 img
    inner_imgs = a.find_all("img")
    print(f"a 内部 img 数: {len(inner_imgs)}")
    for img in inner_imgs:
        print(f"  img src={img.get('src') or img.get('data-src')}")
    # 检查 a 的直接父元素
    parent = a.parent
    print(f"父元素: {parent.name} class={parent.get('class')}")
    # 父元素的 HTML 片段（前 500 字符）
    parent_str = str(parent)[:800]
    print(f"父元素 HTML 片段:\n{parent_str}")

# 看看 .item-wrapper 结构
print("\n=== .item-wrapper 结构 ===")
item_wrappers = soup.find_all("div", class_="item-wrapper")
print(f"item-wrapper 数: {len(item_wrappers)}")
if item_wrappers:
    print(f"\n第一个 item-wrapper 完整结构:")
    print(item_wrappers[0].prettify()[:1500])

# 看看 .ba-item-group 结构
print("\n=== .ba-item-group 结构 ===")
ba_groups = soup.find_all("div", class_="ba-item-group")
print(f"ba-item-group 数: {len(ba_groups)}")
if ba_groups:
    print(f"\n第一个 ba-item-group 完整结构:")
    print(ba_groups[0].prettify()[:2000])
