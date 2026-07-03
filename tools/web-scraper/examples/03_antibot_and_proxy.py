"""
Proxy + anti-bot handling example.

Demonstrates:
  * Custom User-Agent (anti-bot baseline)
  * Proxy server via BrowserConfig.proxy (proxy support)
  * Stealth via playwright-stealth (already installed by crawl4ai)
  * Stealth via crawl4ai's magic=True
"""
import asyncio
import os
import sys
from pathlib import Path

# Sandbox-friendly: keep all Crawl4AI state inside the project tree.
os.environ.setdefault(
    "CRAWL4_AI_BASE_DIRECTORY", str(Path(__file__).resolve().parents[1] / ".crawl4ai")
)

from crawl4ai import AsyncWebCrawler, BrowserConfig, CrawlerRunConfig, CacheMode


OUTPUT_DIR = Path(__file__).resolve().parents[1] / "output"


# Replace with your own proxy if you need one. Leaving it empty disables
# the proxy but the example still showcases all other anti-bot options.
PROXY_URL = ""  # e.g. "http://user:pass@127.0.0.1:7890"

# A realistic desktop Chrome User-Agent string helps bypass naive bot checks.
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)


async def main() -> int:
    target = "https://example.com"
    print(f"[antibot] target: {target}")
    print(f"[antibot] proxy : {PROXY_URL or '<none>'}")

    browser_kwargs = dict(
        headless=True,
        user_agent=USER_AGENT,
    )
    if PROXY_URL:
        browser_kwargs["proxy"] = PROXY_URL

    browser_config = BrowserConfig(**browser_kwargs)
    # magic=True enables crawl4ai's built-in anti-detection tweaks.
    run_config = CrawlerRunConfig(
        cache_mode=CacheMode.BYPASS,
        magic=True,
    )

    try:
        async with AsyncWebCrawler(config=browser_config) as crawler:
            result = await crawler.arun(url=target, config=run_config)
    except Exception as exc:
        print(f"[antibot] FAIL: {exc!r}")
        return 1

    if not result.success:
        print(f"[antibot] FAIL: {result.error_message}")
        return 1

    md = result.markdown.fit_markdown or result.markdown.raw_markdown or ""
    out_file = OUTPUT_DIR / "antibot_example.md"
    out_file.write_text(md, encoding="utf-8")
    print(f"[antibot] OK  saved_to={out_file}  chars={len(md)}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
