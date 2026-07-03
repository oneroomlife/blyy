"""分析 JS 渲染后的语音页面结构，查找音频数据"""
import sys
import io
import re
from pathlib import Path
from bs4 import BeautifulSoup

# 修复 Windows GBK 编码问题
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

html = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students\student_voice_js.html").read_text(encoding="utf-8")
print(f"HTML 长度: {len(html)}")

soup = BeautifulSoup(html, "html.parser")

# 1. 查找所有音频相关元素
print("\n=== 音频元素统计 ===")
audio_tags = soup.find_all("audio")
print(f"<audio> 标签: {len(audio_tags)}")

# 2. 分析 audio 标签的 src
print("\n=== audio 标签 src 分析 ===")
audio_srcs = []
for i, audio in enumerate(audio_tags[:10]):
    src = audio.get("src", "")
    data_src = audio.get("data-src", "")
    print(f"audio[{i}]: src={src[:100]}, data-src={data_src[:100]}")
    if src:
        audio_srcs.append(src)
    if data_src:
        audio_srcs.append(data_src)

# 3. 查找 voice-list-box 内容
print("\n=== voice-list-box 内容 ===")
voice_list_boxes = soup.find_all(class_="voice-list-box")
print(f"voice-list-box 数量: {len(voice_list_boxes)}")
if voice_list_boxes:
    # 打印第一个的文本结构
    vlb = voice_list_boxes[0]
    # 查找其中的所有项
    items = vlb.find_all(class_=re.compile(r"voice-tab-item|voice-item|item"))
    print(f"voice-list-box 内 item 数: {len(items)}")
    if items:
        print("\n--- 第一个 item 结构 ---")
        print(items[0].prettify()[:1500])

# 4. 查找 voice-tab-item
print("\n=== voice-tab-item 内容 ===")
voice_tab_items = soup.find_all(class_="voice-tab-item")
print(f"voice-tab-item 数量: {len(voice_tab_items)}")
if voice_tab_items:
    print("\n--- 第一个 voice-tab-item ---")
    print(voice_tab_items[0].prettify()[:2000])

# 5. 查找 voice-container
print("\n=== voice-container 内容 ===")
voice_containers = soup.find_all(class_="voice-container")
print(f"voice-container 数量: {len(voice_containers)}")
if voice_containers:
    print("\n--- 第一个 voice-container ---")
    print(voice_containers[0].prettify()[:2000])

# 6. 查找 voice-header
print("\n=== voice-header 内容 ===")
voice_headers = soup.find_all(class_="voice-header")
print(f"voice-header 数量: {len(voice_headers)}")
if voice_headers:
    print("\n--- 第一个 voice-header ---")
    print(voice_headers[0].prettify()[:1000])

# 7. 查找 custom-media
print("\n=== custom-media 内容 ===")
custom_media = soup.find_all(class_="custom-media")
print(f"custom-media 数量: {len(custom_media)}")
if custom_media:
    print("\n--- 第一个 custom-media ---")
    print(custom_media[0].prettify()[:1500])

# 8. 查找所有包含音频的元素
print("\n=== 带 audio 子元素的容器 ===")
audio_parents = set()
for audio in audio_tags:
    parent = audio.parent
    while parent:
        classes = parent.get("class", [])
        if classes:
            audio_parents.add(tuple(classes))
        parent = parent.parent
        if parent and parent.name == "body":
            break
print(f"audio 祖先类组合: {list(audio_parents)[:10]}")

# 9. 查找文本内容 - 语音台词
print("\n=== 语音台词文本 ===")
voice_translator = soup.find_all(class_="voice-translator")
print(f"voice-translator 数量: {len(voice_translator)}")
if voice_translator:
    print(voice_translator[0].prettify()[:500])

# 10. 查找所有文本节点
print("\n=== voice-tab-item 完整结构分析 ===")
if voice_tab_items:
    item = voice_tab_items[0]
    print(f"标签名: {item.name}")
    print(f"类: {item.get('class')}")
    print(f"直接文本: {item.get_text(strip=True)[:200]}")
    print(f"子元素:")
    for child in item.children:
        if hasattr(child, 'name') and child.name:
            print(f"  <{child.name} class='{child.get('class', '')}'>: {child.get_text(strip=True)[:100]}")
