"""
使用 JavaScript 执行重新爬取语音和立绘页面，触发动态内容加载。
- 语音页: 等待 voice-box 内容加载
- 立绘页: 依次点击每个 tab 加载所有分类内容
"""
import asyncio
import sys
import os
from pathlib import Path

os.environ.setdefault(
    "CRAWL4_AI_BASE_DIRECTORY",
    r"d:\Android\Project\blyy\tools\web-scraper\.crawl4ai_home",
)

sys.path.insert(0, r"D:\Python\python\Lib\site-packages")

from crawl4ai import AsyncWebCrawler, BrowserConfig, CrawlerRunConfig, CacheMode

OUT_DIR = Path(r"d:\Android\Project\blyy\tools\web-scraper\docs\ba_students")
OUT_DIR.mkdir(parents=True, exist_ok=True)


# 点击所有 tab 并等待内容加载的 JS 代码（立绘页）
GALLERY_TAB_JS = """
(async () => {
    // 等待 tab-box 出现
    await new Promise(r => {
        const check = () => {
            if (document.querySelector('.tab-box')) r();
            else setTimeout(check, 200);
        };
        check();
    });

    // 获取所有 tab
    const tabs = document.querySelectorAll('.tab-box .tab, .tab-box .normal-tab');
    console.log('Found tabs:', tabs.length);

    // 依次点击每个 tab，等待内容加载
    for (let i = 0; i < tabs.length; i++) {
        console.log('Clicking tab', i, tabs[i].textContent.trim());
        tabs[i].click();
        // 等待内容加载
        await new Promise(r => setTimeout(r, 1500));
    }

    // 回到第一个 tab
    if (tabs.length > 0) {
        tabs[0].click();
        await new Promise(r => setTimeout(r, 1000));
    }
})();
"""

# 语音页 JS - 等待语音内容加载并滚动触发懒加载
VOICE_JS = """
(async () => {
    // 等待 voice-box 出现
    await new Promise(r => {
        const check = () => {
            if (document.querySelector('.voice-box, .voice-container, .voice-list-box')) r();
            else setTimeout(check, 200);
        };
        check();
    });

    // 滚动页面触发懒加载
    const scrollHeight = document.body.scrollHeight;
    const step = 500;
    for (let y = 0; y < scrollHeight; y += step) {
        window.scrollTo(0, y);
        await new Promise(r => setTimeout(r, 300));
    }
    window.scrollTo(0, 0);
    await new Promise(r => setTimeout(r, 1000));

    // 再次滚动到底部
    window.scrollTo(0, document.body.scrollHeight);
    await new Promise(r => setTimeout(r, 2000));
    window.scrollTo(0, 0);
    await new Promise(r => setTimeout(r, 1000));
})();
"""


async def crawl_with_js(crawler: AsyncWebCrawler, url: str, name: str, js_code: str):
    run_config = CrawlerRunConfig(
        cache_mode=CacheMode.BYPASS,
        scan_full_page=True,
        scroll_delay=2.0,
        page_timeout=120000,
        delay_before_return_html=6.0,
        js_code=js_code,
    )

    print(f"[{name}] 开始爬取(带JS): {url}")
    result = await crawler.arun(url=url, config=run_config)

    html_path = OUT_DIR / f"{name}_js.html"
    md_path = OUT_DIR / f"{name}_js.md"

    if result.html:
        html_path.write_text(result.html, encoding="utf-8")
        print(f"[{name}] HTML 保存至: {html_path} (长度: {len(result.html)})")
    else:
        print(f"[{name}] 警告: 未获取到 html")

    if result.markdown:
        md_text = result.markdown.fit_markdown or result.markdown.raw_markdown or ""
        md_path.write_text(md_text, encoding="utf-8")
        print(f"[{name}] Markdown 保存至: {md_path} (长度: {len(md_text)})")

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

    async with AsyncWebCrawler(config=browser_config) as crawler:
        # 语音页
        try:
            await crawl_with_js(
                crawler,
                "https://www.gamekee.com/ba/tj/59934.html?tab=2",
                "student_voice",
                VOICE_JS,
            )
        except Exception as e:
            print(f"[student_voice] 爬取失败: {e}")

        # 立绘页
        try:
            await crawl_with_js(
                crawler,
                "https://www.gamekee.com/ba/tj/59934.html?tab=3",
                "student_gallery",
                GALLERY_TAB_JS,
            )
        except Exception as e:
            print(f"[student_gallery] 爬取失败: {e}")

    print("全部任务完成")


if __name__ == "__main__":
    asyncio.run(main())
