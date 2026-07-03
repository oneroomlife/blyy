"""
Deep crawl + export example.

Demonstrates:
  * BFS deep-crawl strategy (multi-page traversal)
  * Markdown content filtering with BM25
  * JSON + Markdown export to disk
"""
import asyncio
import json
import os
import sys
from pathlib import Path

# Sandbox-friendly: keep all Crawl4AI state inside the project tree.
os.environ.setdefault(
    "CRAWL4_AI_BASE_DIRECTORY", str(Path(__file__).resolve().parents[1] / ".crawl4ai")
)

from crawl4ai import (
    AsyncWebCrawler,
    BrowserConfig,
    CrawlerRunConfig,
    CacheMode,
    BFSDeepCrawlStrategy,
)
from crawl4ai.content_filter_strategy import BM25ContentFilter
from crawl4ai.markdown_generation_strategy import DefaultMarkdownGenerator


OUTPUT_DIR = Path(__file__).resolve().parents[1] / "output"


async def main() -> int:
    target = "https://example.com"
    print(f"[deep] target: {target}")

    run_config = CrawlerRunConfig(
        cache_mode=CacheMode.BYPASS,
        deep_crawl_strategy=BFSDeepCrawlStrategy(max_depth=1, max_pages=3),
        markdown_generator=DefaultMarkdownGenerator(
            content_filter=BM25ContentFilter(
                user_query="example domain information",
                bm25_threshold=1.0,
            )
        ),
    )

    try:
        async with AsyncWebCrawler(config=BrowserConfig(headless=True)) as crawler:
            results = await crawler.arun(url=target, config=run_config)
    except Exception as exc:
        print(f"[deep] FAIL: {exc!r}")
        return 1

    # Deep-crawl returns a list of results
    if isinstance(results, list):
        if not results:
            print("[deep] FAIL: empty result list")
            return 1
        result = results[0]
    else:
        result = results

    if not result.success:
        print(f"[deep] FAIL: {result.error_message}")
        return 1

    md = result.markdown.fit_markdown or result.markdown.raw_markdown or ""
    json_data = {
        "url": result.url,
        "title": (result.metadata or {}).get("title"),
        "markdown_chars": len(md),
    }

    (OUTPUT_DIR / "deep_example.md").write_text(md, encoding="utf-8")
    (OUTPUT_DIR / "deep_example.json").write_text(
        json.dumps(json_data, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    print(f"[deep] OK  pages={len(results) if isinstance(results, list) else 1}")
    print(f"[deep] OK  saved_to={OUTPUT_DIR/'deep_example.md'}")
    print(f"[deep] OK  saved_to={OUTPUT_DIR/'deep_example.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
