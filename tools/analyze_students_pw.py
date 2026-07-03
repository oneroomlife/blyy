import asyncio, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

async def main():
    from playwright.async_api import async_playwright
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page(
            user_agent='Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36'
        )
        await page.goto('https://www.gamekee.com/ba/second/23941', wait_until='networkidle', timeout=30000)
        await page.wait_for_timeout(3000)
        html = await page.content()
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
            '.wiki-card',
            '.wiki-list',
        ]
        for sel in selectors:
            try:
                found = soup.select(sel)
                print(f'{sel}: {len(found)} matches')
                if found and len(found) > 0:
                    print(f'  First: {str(found[0])[:400]}')
            except Exception as e:
                print(f'{sel}: ERROR - {e}')
        await browser.close()

asyncio.run(main())
