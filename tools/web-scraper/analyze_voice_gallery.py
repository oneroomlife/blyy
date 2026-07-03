"""分析 gamekee 学生语音和立绘页面 HTML 结构"""
import sys
import re
import json
from pathlib import Path

sys.path.insert(0, r"D:\Python\python\Lib\site-packages")
from bs4 import BeautifulSoup

VOICE_HTML = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\student_voice.html")
GALLERY_HTML = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\student_gallery.html")

print("=" * 80)
print("=== 语音页面分析 ===")
print("=" * 80)
voice_html = VOICE_HTML.read_text(encoding="utf-8", errors="ignore")
print(f"HTML 长度: {len(voice_html)}")
voice_soup = BeautifulSoup(voice_html, "html.parser")

# 找到所有音频链接
audio_links = voice_soup.find_all("audio")
print(f"\n<audio> 标签数: {len(audio_links)}")
for i, a in enumerate(audio_links[:5]):
    print(f"{i}: {a}")

# 找到所有 mp3 链接
mp3_links = voice_soup.find_all("a", href=re.compile(r"\.mp3"))
print(f"\nmp3 链接数: {len(mp3_links)}")
for i, a in enumerate(mp3_links[:5]):
    print(f"{i}: href={a.get('href')} text={a.get_text(strip=True)!r}")

# 找到所有 source 标签
sources = voice_soup.find_all("source")
print(f"\n<source> 标签数: {len(sources)}")
for i, s in enumerate(sources[:5]):
    print(f"{i}: src={s.get('src')} type={s.get('type')}")

# 找到所有带音频的按钮
print("\n=== 查找音频按钮 ===")
audio_btns = voice_soup.find_all(class_=re.compile(r"audio|voice|play|sound", re.I))
print(f"音频相关 class 元素数: {len(audio_btns)}")
for i, b in enumerate(audio_btns[:5]):
    print(f"{i}: {b.name} class={b.get('class')}")

# 找到所有表格
tables = voice_soup.find_all("table")
print(f"\n表格数: {len(tables)}")
for i, t in enumerate(tables[:3]):
    print(f"\n--- 表格 {i} ---")
    print(t.prettify()[:800])

# 找到所有 mp3/ogg URL（包括在 script 中）
print("\n=== 在 script 中查找音频 URL ===")
scripts = voice_soup.find_all("script")
print(f"script 标签数: {len(scripts)}")
audio_urls_in_scripts = []
for s in scripts:
    text = s.get_text() or ""
    urls = re.findall(r'https?://[^"\']+\.mp3', text)
    audio_urls_in_scripts.extend(urls)
print(f"script 中 mp3 URL 数: {len(audio_urls_in_scripts)}")
for u in audio_urls_in_scripts[:5]:
    print(f"  {u}")

# 找到所有文本中 mp3 URL
all_text = voice_soup.get_text()
all_mp3 = re.findall(r'https?://[^"\'>\s]+\.mp3', all_text)
print(f"\n页面文本中 mp3 URL 数: {len(all_mp3)}")
for u in all_mp3[:5]:
    print(f"  {u}")

# 查找语音表格行
print("\n=== 查找语音表格行 ===")
rows = voice_soup.find_all("tr")
print(f"tr 数: {len(rows)}")
for i, r in enumerate(rows[:5]):
    print(f"\n--- 行 {i} ---")
    print(r.prettify()[:600])

print("\n\n")
print("=" * 80)
print("=== 立绘页面分析 ===")
print("=" * 80)
gallery_html = GALLERY_HTML.read_text(encoding="utf-8", errors="ignore")
print(f"HTML 长度: {len(gallery_html)}")
gallery_soup = BeautifulSoup(gallery_html, "html.parser")

# 找到所有图片
all_imgs = gallery_soup.find_all("img")
print(f"\n图片总数: {len(all_imgs)}")
for i, img in enumerate(all_imgs[:10]):
    src = img.get("src") or img.get("data-src") or ""
    alt = img.get("alt", "")
    print(f"{i}: src={src[:120]} alt={alt!r}")

# 查找标签页结构
print("\n=== 查找标签页 ===")
tabs = gallery_soup.find_all(class_=re.compile(r"tab", re.I))
print(f"tab 相关元素数: {len(tabs)}")
for i, t in enumerate(tabs[:10]):
    print(f"{i}: {t.name} class={t.get('class')} text={t.get_text(strip=True)!r}")

# 查找分类标题
print("\n=== 查找分类标题 ===")
headers = gallery_soup.find_all(["h1", "h2", "h3", "h4", "h5", "h6"])
print(f"标题数: {len(headers)}")
for i, h in enumerate(headers[:20]):
    print(f"{i}: {h.name} text={h.get_text(strip=True)!r}")

# 查找包含"官方介绍"、"回忆大厅"、"设定集"等关键词的元素
print("\n=== 查找分类关键词 ===")
keywords = ["官方介绍", "回忆大厅", "设定集", "本家画", "官方衍生", "表情", "视频"]
for kw in keywords:
    elements = gallery_soup.find_all(string=re.compile(kw))
    print(f"'{kw}': 找到 {len(elements)} 处")
    for e in elements[:2]:
        parent = e.parent
        print(f"  parent: {parent.name} class={parent.get('class')}")
        print(f"  text: {str(e).strip()[:100]}")
