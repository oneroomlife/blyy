"""
爬取学生档案相关数据源：
1. 学生列表: https://www.gamekee.com/ba/second/23941
2. 学生语音: https://www.gamekee.com/ba/tj/59934.html?tab=2
3. 学生立绘: https://www.gamekee.com/ba/tj/59934.html?tab=3
"""
import asyncio
import sys
import os
import json
from pathlib import Path

# 设置 crawl4ai 的 base 目录到可写位置，避免默认 home 目录权限问题
os.environ.setdefault(
    "CRAWL4_AI_BASE_DIRECTORY",
    r"d:\Android\Project\blyy\tools\web-scraper\.crawl4ai_home",
)

# 使用项目内已安装的 crawl4ai
sys.path.insert(0, r"D:\Python\python\Lib\site-packages")

from crawl4ai import AsyncWebCrawler, BrowserConfig, CrawlerRunConfig, CacheMode
from crawl4ai.content_filter_strategy import PruningContentFilter
from crawl4ai.markdown_generation_strategy import DefaultMarkdownGenerator

OUT_DIR = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students")
OUT_DIR.mkdir(parents=True, exist_ok=True)


async def crawl_one(crawler: AsyncWebCrawler, url: str, name: str, wait_selector: str = None):
    """爬取单个 URL 并保存原始 markdown / html"""
    run_config_kwargs = dict(
        cache_mode=CacheMode.BYPASS,
        markdown_generator=DefaultMarkdownGenerator(
            content_filter=PruningContentFilter(
                threshold=0.48,
                threshold_type="fixed",
                min_word_threshold=0
            )
        ),
        scan_full_page=True,
        scroll_delay=1.2,
        page_timeout=90000,
        delay_before_return_html=4.0,
        js_code=[
            "window.scrollTo(0, document.body.scrollHeight);",
            "window.scrollTo(0, 0);",
        ],
    )
    if wait_selector:
        run_config_kwargs["wait_for"] = wait_selector

    run_config = CrawlerRunConfig(**run_config_kwargs)

    print(f"[{name}] 开始爬取: {url}")
    result = await crawler.arun(url=url, config=run_config)

    md_path = OUT_DIR / f"{name}.md"
    html_path = OUT_DIR / f"{name}.html"

    if result.markdown:
        md_text = result.markdown.fit_markdown or result.markdown.raw_markdown or ""
        md_path.write_text(md_text, encoding="utf-8")
        print(f"[{name}] Markdown 保存至: {md_path} (长度: {len(md_text)})")
    else:
        print(f"[{name}] 警告: 未获取到 markdown")

    if result.html:
        html_path.write_text(result.html, encoding="utf-8")
        print(f"[{name}] HTML 保存至: {html_path} (长度: {len(result.html)})")
    else:
        print(f"[{name}] 警告: 未获取到 html")

    # 保存截图便于排查
    try:
        if result.screenshot:
            shot_path = OUT_DIR / f"{name}.png"
            shot_path.write_bytes(result.screenshot)
            print(f"[{name}] 截图保存至: {shot_path}")
    except Exception as e:
        print(f"[{name}] 截图保存失败: {e}")

    return result


async def main():
    browser_config = BrowserConfig(
        headless=True,
        verbose=False,
        viewport_width=1440,
        viewport_height=900,
        user_agent=(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        ),
    )

    targets = [
        ("https://www.gamekee.com/ba/second/23941", "students_list"),
        ("https://www.gamekee.com/ba/tj/59934.html?tab=2", "student_voice"),
        ("https://www.gamekee.com/ba/tj/59934.html?tab=3", "student_gallery"),
    ]

    async with AsyncWebCrawler(config=browser_config) as crawler:
        for url, name in targets:
            try:
                await crawl_one(crawler, url, name)
            except Exception as e:
                print(f"[{name}] 爬取失败: {e}")

    print("全部任务完成")


if __name__ == "__main__":
    asyncio.run(main())
