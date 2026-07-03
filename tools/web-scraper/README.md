# Web Scraper Tool (Crawl4AI)

A self-contained, Python-based web scraping and data analysis toolkit installed
under `tools/web-scraper/`. Built on top of **[Crawl4AI](https://github.com/unclecode/crawl4ai)**
(66k+ GitHub stars, Apache-2.0).

This is a **standalone Python tool**, intentionally kept outside the Android
(`app/`) source tree so it never affects the Android build or Gradle graph.

## Why Crawl4AI?

| Requirement                | How Crawl4AI satisfies it                                                                 |
| -------------------------- | ----------------------------------------------------------------------------------------- |
| Open source & ≥ 5k stars   | Apache-2.0, 66k+ stars on GitHub.                                                         |
| Web content extraction     | Async browser pool built on Playwright + Patchright; renders JS, returns clean Markdown.  |
| Data parsing               | Multiple strategies: JSON-CSS, JSON-XPath, LLM, regex, BM25 content filter.              |
| Request proxy support      | `BrowserConfig.proxy` accepts HTTP/SOCKS proxies; `RoundRobinProxyStrategy` for rotation.|
| Anti-crawling handling     | `magic=True`, stealth User-Agents, `playwright-stealth`, custom headers, slow-rendering.  |
| Data storage & export      | Built-in SQLite cache + on-disk Markdown/JSON/PDF/PNG outputs to `output/`.               |

## Directory layout

```
tools/web-scraper/
├── README.md                 <- this file
├── .crawl4ai/                <- runtime cache (auto-created)
├── docs/                     <- install logs, smoke test logs
├── examples/                 <- ready-to-run Python scripts
│   ├── 01_basic_crawl.py
│   ├── 02_structured_extraction.py
│   ├── 03_antibot_and_proxy.py
│   └── 04_deep_crawl_and_export.py
└── output/                   <- crawl results land here
```

## Installation (already done in this workspace)

The installer was tested and works on this machine. The steps below are kept so
you can re-run them in a clean environment.

### 1. Prerequisites

* Python **3.10+** (3.12 was used here, at `D:\Python\python\python.exe`).
* `pip` 24.2 or newer.

### 2. Install the package

> **Why a mirror?** On the developer network, `pypi.org` timeouts were
> observed while building `lxml`. A Chinese mirror (`tuna.tsinghua.edu.cn`) is
> configured by default; switch back to `https://pypi.org/simple` if you are
> outside that network.

```powershell
pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple
pip config set global.timeout 120
python -m pip install --prefer-binary -U crawl4ai
```

`--prefer-binary` ensures pip downloads wheels instead of compiling
`lxml` from source (which requires MSVC build tools on Windows).

### 3. Install the browser

Crawl4AI bundles Playwright + Patchright. One command downloads the
Chromium-for-Testing binary (~180 MB):

```powershell
crawl4ai-setup
```

This is equivalent to `python -m playwright install chromium`.

### 4. Verify

```powershell
python -c "import crawl4ai; print('crawl4ai', crawl4ai.__version__)"
crawl4ai-doctor
```

## Sandbox note (read me!)

Crawl4AI's `RobotsParser` writes to a SQLite database under
`Path.home() / .crawl4ai / robots / robots_cache.db` using `PRAGMA
journal_mode=WAL`. **In some sandboxes (including this Windows sandbox) the
WAL file cannot be created in the user profile**, which raises
`sqlite3.OperationalError: unable to open database file`.

**Workaround:** every example sets the env var
`CRAWL4_AI_BASE_DIRECTORY` to a directory inside the project so all Crawl4AI
state (cache, robots DB, models, content) lives in `tools/web-scraper/.crawl4ai/`
and is fully self-contained. If you run your own scripts outside these
examples, set the same env var:

```python
import os
os.environ["CRAWL4_AI_BASE_DIRECTORY"] = r"D:\path\to\tools\web-scraper\.crawl4ai"
```

## Usage

Run any example from the project root:

```powershell
# 1. basic Markdown extraction
python tools\web-scraper\examples\01_basic_crawl.py

# 2. JSON-CSS structured extraction
python tools\web-scraper\examples\02_structured_extraction.py

# 3. anti-bot + (optional) proxy
python tools\web-scraper\examples\03_antibot_and_proxy.py

# 4. deep crawl + BM25 filtering + dual export
python tools\web-scraper\examples\04_deep_crawl_and_export.py
```

All outputs are written to `tools/web-scraper/output/`.

### Example 1 — `01_basic_crawl.py`

Bare-minimum usage; returns clean Markdown for any URL.

```python
from crawl4ai import AsyncWebCrawler, BrowserConfig, CrawlerRunConfig, CacheMode

async with AsyncWebCrawler(config=BrowserConfig(headless=True)) as crawler:
    result = await crawler.arun(
        url="https://example.com",
        config=CrawlerRunConfig(cache_mode=CacheMode.BYPASS),
    )
print(result.markdown.fit_markdown)
```

### Example 2 — `02_structured_extraction.py`

Schema-based extraction (no LLM required):

```python
schema = {
    "name": "Product Card",
    "baseSelector": "div.product",
    "fields": [
        {"name": "title", "selector": "h3", "type": "text"},
        {"name": "price", "selector": "span.price", "type": "text"},
    ],
}
run_config = CrawlerRunConfig(
    extraction_strategy=JsonCssExtractionStrategy(schema),
)
```

### Example 3 — `03_antibot_and_proxy.py`

`BrowserConfig.proxy` enables HTTP/SOCKS proxies. The default User-Agent is
rotated, and `magic=True` enables crawl4ai's bundled anti-detection tweaks.

```python
browser_config = BrowserConfig(
    headless=True,
    proxy="http://user:pass@127.0.0.1:7890",   # leave empty to skip
    user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) ...",
)
run_config = CrawlerRunConfig(magic=True)
```

### Example 4 — `04_deep_crawl_and_export.py`

BFS deep-crawl up to 3 pages, filter the content with BM25 against a custom
query, and save both Markdown and JSON side-by-side.

## Data export options

Crawl4AI supports several out-of-the-box export channels; pick what fits:

| Format    | How to enable                                       | Where it lands                                    |
| --------- | --------------------------------------------------- | ------------------------------------------------- |
| Markdown  | Always available on `result.markdown`               | Manually save in your script (see all examples).  |
| JSON      | `extraction_strategy=JsonCssExtractionStrategy(...)`| `result.extracted_content` (already a JSON str).  |
| Screenshot| `CrawlerRunConfig(screenshot=True)`                 | `result.screenshot` (PNG bytes).                  |
| PDF       | `CrawlerRunConfig(pdf=True)`                        | `result.pdf` (PDF bytes).                         |
| SQLite    | `CacheMode.ENABLED` (default)                       | `.crawl4ai/crawl4ai.db`                           |
| Files     | `CrawlerRunConfig(...).scraping_strategy=...`       | `.crawl4ai/{markdown_content,html_content,...}/`  |

## Troubleshooting

| Symptom                                                         | Fix                                                                                  |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `Microsoft Visual C++ 14.0 or greater is required` (lxml)        | Reinstall with `--prefer-binary`; lxml has a wheel for Python 3.12.                  |
| `sqlite3.OperationalError: unable to open database file`        | Set `CRAWL4_AI_BASE_DIRECTORY` to a writable folder (see "Sandbox note" above).      |
| `BrowserType.launch: Executable doesn't exist`                  | Run `crawl4ai-setup` again.                                                          |
| `pypi.org` timeouts during install                              | Switch to a mirror, e.g. `pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple`. |
| Anti-bot still 403s                                            | Combine `proxy=` + custom `user_agent=` + `magic=True`; consider Patchright adapter.|

## Verification (already performed)

| Test                                       | Result | Log                                  |
| ------------------------------------------ | ------ | ------------------------------------ |
| `pip install -U crawl4ai`                  | OK (v0.9.0 + 76 deps) | `docs/install.log`          |
| `crawl4ai-setup` (Playwright Chromium)     | OK     | `docs/setup.log`                     |
| `01_basic_crawl.py`                        | OK     | `docs/smoke.log`                     |
| `02_structured_extraction.py`              | OK     | `docs/structured.log`                |
| `03_antibot_and_proxy.py`                  | OK     | `docs/antibot.log`                   |
| `04_deep_crawl_and_export.py`              | OK     | `docs/deep.log`                      |

Sample output saved to `output/`:

```
smoke_example.md        -> "Example Domain" + cleaned paragraph + link
structured_example.json -> JSON array of {h1, paragraphs, links}
antibot_example.md      -> same as smoke (no proxy active)
deep_example.{md,json}  -> first page Markdown + metadata JSON
```

## Resources

* GitHub: <https://github.com/unclecode/crawl4ai>
* Docs:   <https://docs.crawl4ai.com/>
* License: Apache-2.0
