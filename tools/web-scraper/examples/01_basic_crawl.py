"""
Basic Crawl4AI smoke test.
Verifies the tool can launch, fetch a page, and produce clean Markdown.
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
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


async def main() -> int:
    target = "https://example.com"
    print(f"[smoke] target: {target}")

    browser_config = BrowserConfig(headless=True, verbose=False)
    run_config = CrawlerRunConfig(cache_mode=CacheMode.BYPASS)

    try:
        async with AsyncWebCrawler(config=browser_config) as crawler:
            result = await crawler.arun(url=target, config=run_config)
    except Exception as exc:  # pragma: no cover - diagnostic only
        print(f"[smoke] FAIL: {exc!r}")
        return 1

    if not result.success:
        print(f"[smoke] FAIL: crawl unsuccessful - {result.error_message}")
        return 1

    md = result.markdown.fit_markdown or result.markdown.raw_markdown or ""
    if not md.strip():
        print("[smoke] FAIL: empty markdown returned")
        return 1

    out_file = OUTPUT_DIR / "smoke_example.md"
    out_file.write_text(md, encoding="utf-8")

    title = (result.metadata or {}).get("title", "<no title>")
    print(f"[smoke] OK  title={title!r}")
    print(f"[smoke] OK  markdown_chars={len(md)}")
    print(f"[smoke] OK  saved_to={out_file}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
