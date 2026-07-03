"""
Structured extraction example.
Demonstrates schema-based JSON CSS extraction without LLM.
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

from crawl4ai import AsyncWebCrawler, BrowserConfig, CrawlerRunConfig, CacheMode
from crawl4ai import JsonCssExtractionStrategy


OUTPUT_DIR = Path(__file__).resolve().parents[1] / "output"


# JSON-CSS schema: scrapes the "h1..h3 + paragraph" pairs from example.com
SCHEMA = {
    "name": "Example Domains",
    "baseSelector": "body",
    "fields": [
        {"name": "h1", "selector": "h1", "type": "text"},
        {"name": "h2_list", "selector": "h2", "type": "text"},
        {"name": "paragraphs", "selector": "p", "type": "text"},
        {"name": "links", "selector": "a", "type": "attribute", "attribute": "href"},
    ],
}


async def main() -> int:
    target = "https://example.com"
    print(f"[structured] target: {target}")

    browser_config = BrowserConfig(headless=True)
    run_config = CrawlerRunConfig(
        cache_mode=CacheMode.BYPASS,
        extraction_strategy=JsonCssExtractionStrategy(SCHEMA),
    )

    try:
        async with AsyncWebCrawler(config=browser_config) as crawler:
            result = await crawler.arun(url=target, config=run_config)
    except Exception as exc:
        print(f"[structured] FAIL: {exc!r}")
        return 1

    if not result.success:
        print(f"[structured] FAIL: {result.error_message}")
        return 1

    raw = result.extracted_content or "[]"
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        data = raw

    out_file = OUTPUT_DIR / "structured_example.json"
    out_file.write_text(
        json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    print(f"[structured] OK  saved_to={out_file}")
    print(f"[structured] OK  items={len(data) if isinstance(data, list) else 1}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
