"""分析 gamekee 学生列表 HTML 结构，提取学生数据"""
import sys
import os
import re
import json
from pathlib import Path

sys.path.insert(0, r"D:\Python\python\Lib\site-packages")
from bs4 import BeautifulSoup

HTML_PATH = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\students_list.html")
OUT_JSON = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\students_list.json")

html = HTML_PATH.read_text(encoding="utf-8", errors="ignore")
print(f"HTML 长度: {len(html)}")

soup = BeautifulSoup(html, "html.parser")

# 找到所有学生链接 - gamekee 学生详情页通常包含 /ba/tj/ 或 /ba/ 路径
# 先看看页面结构
print("\n=== 探查页面结构 ===")
# 找到所有图片
all_imgs = soup.find_all("img")
print(f"图片总数: {len(all_imgs)}")

# 找到所有链接
all_links = soup.find_all("a", href=True)
print(f"链接总数: {len(all_links)}")

# 看看前 20 个链接
print("\n=== 前 20 个链接 ===")
for i, a in enumerate(all_links[:20]):
    href = a.get("href", "")
    text = a.get_text(strip=True)
    title = a.get("title", "")
    print(f"{i}: href={href} text={text!r} title={title!r}")

# 找到所有学生卡片样式 - gamekee 通常用 .wiki-card, .char-card, .avatar 等
print("\n=== 探查卡片结构 ===")
# 常见 class 名
candidate_classes = ["char", "card", "avatar", "student", "unit", "role", "wiki", "list"]
for cls in candidate_classes:
    elements = soup.find_all(class_=re.compile(cls, re.I))
    if elements:
        print(f"class 含 '{cls}': {len(elements)} 个")
        if elements:
            print(f"  示例: {elements[0].name} class={elements[0].get('class')}")

# 找到所有 /ba/tj/ 链接（学生详情页）
print("\n=== /ba/tj/ 学生详情链接 ===")
tj_links = [a for a in all_links if "/ba/tj/" in a.get("href", "")]
print(f"/ba/tj/ 链接数: {len(tj_links)}")
for i, a in enumerate(tj_links[:5]):
    print(f"{i}: href={a.get('href')} text={a.get_text(strip=True)!r} title={a.get('title')!r}")
    # 看看父元素
    parent = a.parent
    if parent:
        print(f"   parent: {parent.name} class={parent.get('class')}")
        # 看看父元素里的图片
        imgs = parent.find_all("img")
        for img in imgs[:3]:
            print(f"   img: src={img.get('src') or img.get('data-src')} alt={img.get('alt')}")

# 找到所有 /ba/ 链接（NPC 等）
print("\n=== /ba/ NPC 链接 ===")
ba_links = [a for a in all_links if a.get("href", "").startswith("/ba/") and "/ba/tj/" not in a.get("href", "")]
print(f"/ba/ 非 tj 链接数: {len(ba_links)}")
for i, a in enumerate(ba_links[:5]):
    print(f"{i}: href={a.get('href')} text={a.get_text(strip=True)!r}")

# 看看包含图片的链接（学生头像）
print("\n=== 包含图片的链接 ===")
links_with_img = [a for a in all_links if a.find("img")]
print(f"含图片的链接数: {len(links_with_img)}")
for i, a in enumerate(links_with_img[:5]):
    img = a.find("img")
    print(f"{i}: href={a.get('href')} text={a.get_text(strip=True)!r}")
    print(f"   img: src={img.get('src') or img.get('data-src')} alt={img.get('alt')}")

# 保存所有学生数据
print("\n=== 提取所有学生数据 ===")
students = []
seen_names = set()

# 优先从 /ba/tj/ 链接提取
for a in tj_links:
    href = a.get("href", "")
    name = a.get("title", "") or a.get_text(strip=True)
    if not name or not href:
        continue
    # 完整 URL
    if href.startswith("/"):
        full_url = "https://www.gamekee.com" + href
    else:
        full_url = href
    # 提取学生 ID
    match = re.search(r"/ba/tj/(\d+)\.html", href)
    student_id = match.group(1) if match else ""
    
    # 查找头像
    avatar_url = ""
    parent = a.parent
    if parent:
        img = parent.find("img")
        if img:
            avatar_url = img.get("src") or img.get("data-src") or ""
    
    # 清理名字
    name = name.replace("\\", "").replace("(", "（").replace(")", "）").strip()
    
    if name and name not in seen_names:
        seen_names.add(name)
        students.append({
            "name": name,
            "url": full_url,
            "student_id": student_id,
            "avatar_url": avatar_url,
        })

print(f"\n提取到 {len(students)} 个学生")
print("\n=== 前 10 个学生 ===")
for s in students[:10]:
    print(f"  {s['name']} (id={s['student_id']}) avatar={s['avatar_url'][:80] if s['avatar_url'] else 'N/A'}")

print("\n=== 后 5 个学生 ===")
for s in students[-5:]:
    print(f"  {s['name']} (id={s['student_id']}) avatar={s['avatar_url'][:80] if s['avatar_url'] else 'N/A'}")

# 保存到 JSON
OUT_JSON.write_text(json.dumps(students, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"\n保存至: {OUT_JSON}")
