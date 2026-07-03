import asyncio, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

async def main():
    from crawl4ai import AsyncWebCrawler
    from crawl4ai.async_configs import BrowserConfig, CrawlerRunConfig
    browser_config = BrowserConfig(headless=True, verbose=False)
    run_config = CrawlerRunConfig(page_timeout=30000)
    async with AsyncWebCrawler(config=browser_config) as crawler:
        result = await crawler.arun(url='https://www.gamekee.com/ba/second/23941', config=run_config)
        html = result.html
        print('HTML length:', len(html))
        with open('students_rendered.html', 'w', encoding='utf-8') as f:
            f.write(html)
        from bs4 import BeautifulSoup
        soup = BeautifulSoup(html, 'html.parser')
        selectors = [
            'a.item',
            'a[href*="/ba/tj/"]',
            '.list-item',
            '.card',
            '.char-item',
            '.role-item',
            '.student-item',
            '[class*=item]',
            '[class*=card]',
            '[class*=role]',
            'a[href*="tj"]',
            '.name',
            'span.name',
            '.avatar',
            'img.cover',
            '.detail-list',
            '.list-wrap',
            '.role-list',
            '.char-list',
        ]
        for sel in selectors:
            try:
                found = soup.select(sel)
                print(f'{sel}: {len(found)} matches')
                if found and len(found) > 0:
                    print(f'  First: {str(found[0])[:400]}')
            except Exception as e:
                print(f'{sel}: ERROR - {e}')

asyncio.run(main())
