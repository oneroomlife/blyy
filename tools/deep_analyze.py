import asyncio, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

async def main():
    from playwright.async_api import async_playwright
    from bs4 import BeautifulSoup
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page(
            user_agent='Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36'
        )

        # === Voice page deep analysis ===
        print("="*60)
        print("VOICE PAGE DEEP ANALYSIS")
        print("="*60)
        await page.goto('https://www.gamekee.com/ba/tj/59934.html?tab=2', wait_until='networkidle', timeout=30000)
        await page.wait_for_timeout(5000)
        html = await page.content()
        soup = BeautifulSoup(html, 'html.parser')

        # Get full structure of first list-group-item with dub-items
        lg = soup.select('.list-group#dtjkfg')
        if lg:
            items = lg[0].select('.list-group-item')
            print(f"\ndtjkfg list-group: {len(items)} items")
            if items:
                # Print full HTML of first item with dub-items
                first_item = items[1] if len(items) > 1 else items[0]  # skip header item
                print(f"\nFirst item full HTML (first 2000 chars):")
                print(str(first_item)[:2000])

                # Find all text-containing elements
                print(f"\nText elements in first item:")
                for el in first_item.find_all(True):
                    text = el.get_text(strip=True)
                    cls = el.get('class', [])
                    if text and len(text) > 2:
                        print(f"  <{el.name} class={cls}>: {text[:80]}")

        # === Gallery page deep analysis ===
        print("\n" + "="*60)
        print("GALLERY PAGE DEEP ANALYSIS")
        print("="*60)
        await page.goto('https://www.gamekee.com/ba/tj/59934.html?tab=3', wait_until='networkidle', timeout=30000)
        await page.wait_for_timeout(5000)
        html = await page.content()
        soup = BeautifulSoup(html, 'html.parser')

        # Find tab-like elements
        print("\nLooking for tab navigation elements:")
        tab_selectors = [
            '.tab', '.tabs', '.tab-item', '.nav-tab', '[class*=tab]',
            '.ba-tj-tab', '.tj-tab', '.gallery-tab', '.image-tab',
            '.el-tabs', '.el-tab', '.el-tabs__item',
            '.header', '.section', '.category', '.panel',
        ]
        for sel in tab_selectors:
            found = soup.select(sel)
            if found:
                print(f"  {sel}: {len(found)} matches")
                if len(found) > 0:
                    print(f"    First: {str(found[0])[:200]}")

        # Find all divs with id attributes
        print("\nDivs with id attributes:")
        for el in soup.find_all('div', id=True):
            cls = el.get('class', [])
            imgs = el.find_all('img')
            print(f"  id={el.get('id')} class={cls} imgs={len(imgs)}")

        # Find large images (likely gallery images, not icons)
        print("\nLarge images (w_ > 200 in src):")
        large_imgs = []
        for img in soup.select('img[src]'):
            src = img.get('src', '')
            if 'w_' in src:
                import re
                m = re.search(r'w_(\d+)', src)
                if m and int(m.group(1)) > 200:
                    cls = img.get('class', [])
                    alt = img.get('alt', '')
                    parent = img.parent
                    parent_cls = parent.get('class', []) if parent else []
                    parent_id = parent.get('id', '') if parent else ''
                    large_imgs.append({
                        'src': src[:100],
                        'class': cls,
                        'alt': alt,
                        'parent_class': parent_cls,
                        'parent_id': parent_id,
                    })
        print(f"  Found {len(large_imgs)} large images")
        for i, img in enumerate(large_imgs[:20]):
            print(f"  [{i}] class={img['class']} parent={img['parent_class']} parent_id={img['parent_id']} src={img['src']}")

        # Find the main content area
        print("\nMain content area analysis:")
        main_selectors = ['main', '.main', '.content', '.wiki-content', '.ba-tj', '.tj-content', '.gallery']
        for sel in main_selectors:
            found = soup.select(sel)
            if found:
                print(f"  {sel}: {len(found)} matches")
                if found:
                    # Count images in this area
                    imgs = found[0].select('img')
                    large_count = 0
                    for img in imgs:
                        src = img.get('src', '')
                        if 'w_' in src:
                            import re
                            m = re.search(r'w_(\d+)', src)
                            if m and int(m.group(1)) > 200:
                                large_count += 1
                    print(f"    First element: {len(imgs)} total imgs, {large_count} large imgs")

        await browser.close()

asyncio.run(main())
