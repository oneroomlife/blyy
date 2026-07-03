"""深入分析语音和立绘页面结构"""
import sys
import re
import json
from pathlib import Path

sys.path.insert(0, r"D:\Python\python\Lib\site-packages")
from bs4 import BeautifulSoup

VOICE_HTML = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\student_voice.html")
GALLERY_HTML = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\student_gallery.html")

print("=" * 80)
print("=== 语音页面深入分析 ===")
print("=" * 80)
voice_html = VOICE_HTML.read_text(encoding="utf-8", errors="ignore")
voice_soup = BeautifulSoup(voice_html, "html.parser")

# 查看 voice-box 完整内容
print("\n=== voice-box 内容 ===")
voice_box = voice_soup.find(class_="voice-box")
if voice_box:
    print(voice_box.prettify()[:3000])

# 查看 voice-container 内容
print("\n=== voice-container 内容 ===")
voice_container = voice_soup.find(class_="voice-container")
if voice_container:
    print(voice_container.prettify()[:3000])

# 查看 voice-header 内容
print("\n=== voice-header 内容 ===")
voice_header = voice_soup.find(class_="voice-header")
if voice_header:
    print(voice_header.prettify()[:2000])

# 查找所有 script 内容，搜索 voice/audio 相关数据
print("\n=== script 中 voice/audio 相关内容 ===")
scripts = voice_soup.find_all("script")
for i, s in enumerate(scripts):
    text = s.get_text() or ""
    if any(kw in text.lower() for kw in ["voice", "audio", "mp3", "ogg", "sound"]):
        print(f"\n--- script {i} (含音频关键词) ---")
        print(text[:2000])
        print("..." if len(text) > 2000 else "")

# 查找包含 URL 的 script
print("\n=== script 中所有 URL ===")
for i, s in enumerate(scripts):
    text = s.get_text() or ""
    urls = re.findall(r'https?://[^\s"\'<>]+', text)
    if urls:
        print(f"\nscript {i} 含 {len(urls)} 个 URL:")
        for u in urls[:10]:
            print(f"  {u}")

# 查找页面所有 class
print("\n=== 页面所有 class ===")
all_classes = set()
for el in voice_soup.find_all(class_=True):
    for c in el.get("class", []):
        all_classes.add(c)
print(f"class 总数: {len(all_classes)}")
print(", ".join(sorted(all_classes)))

print("\n\n")
print("=" * 80)
print("=== 立绘页面深入分析 ===")
print("=" * 80)
gallery_html = GALLERY_HTML.read_text(encoding="utf-8", errors="ignore")
gallery_soup = BeautifulSoup(gallery_html, "html.parser")

# 查看 tab-box 内容
print("\n=== tab-box 内容 ===")
tab_box = gallery_soup.find(class_="tab-box")
if tab_box:
    print(tab_box.prettify()[:2000])

# 查找所有 tab 内容容器
print("\n=== 查找 tab 内容容器 ===")
tab_cons = gallery_soup.find_all(class_=re.compile(r"tab-con|tab-content|content|panel", re.I))
print(f"tab 内容容器数: {len(tab_cons)}")
for i, tc in enumerate(tab_cons[:10]):
    print(f"\n--- 容器 {i}: {tc.name} class={tc.get('class')} ---")
    print(tc.prettify()[:800])

# 查找所有图片（非 base64）
print("\n=== 非 base64 图片 ===")
real_imgs = []
for img in gallery_soup.find_all("img"):
    src = img.get("src") or img.get("data-src") or ""
    if src and not src.startswith("data:"):
        real_imgs.append(img)
print(f"非 base64 图片数: {len(real_imgs)}")
for i, img in enumerate(real_imgs[:20]):
    src = img.get("src") or img.get("data-src") or ""
    alt = img.get("alt", "")
    parent = img.parent
    print(f"{i}: src={src[:120]}")
    print(f"   alt={alt!r} parent={parent.name} class={parent.get('class')}")

# 查找所有 class
print("\n=== 页面所有 class ===")
all_classes = set()
for el in gallery_soup.find_all(class_=True):
    for c in el.get("class", []):
        all_classes.add(c)
print(f"class 总数: {len(all_classes)}")
print(", ".join(sorted(all_classes)))

# 查找包含图片的容器
print("\n=== 查找图片容器 ===")
img_containers = gallery_soup.find_all(class_=re.compile(r"img|image|pic|gallery|figure|illustration", re.I))
print(f"图片容器数: {len(img_containers)}")
for i, c in enumerate(img_containers[:10]):
    print(f"\n--- 容器 {i}: {c.name} class={c.get('class')} ---")
    print(c.prettify()[:600])

# 查找所有 script 中的图片 URL
print("\n=== script 中的图片 URL ===")
for i, s in enumerate(gallery_soup.find_all("script")):
    text = s.get_text() or ""
    urls = re.findall(r'https?://[^\s"\'<>]+\.(?:png|jpg|jpeg|webp|gif)', text)
    if urls:
        print(f"\nscript {i} 含 {len(urls)} 个图片 URL:")
        for u in urls[:10]:
            print(f"  {u}")
