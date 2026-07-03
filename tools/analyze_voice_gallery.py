import asyncio, sys, io, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

async def analyze_page(page, url, name):
    print(f"\n{'='*60}")
    print(f"Analyzing {name}: {url}")
    print(f"{'='*60}")
    await page.goto(url, wait_until='networkidle', timeout=30000)
    await page.wait_for_timeout(5000)
    html = await page.content()
    print(f"HTML length: {len(html)}")

    from bs4 import BeautifulSoup
    soup = BeautifulSoup(html, 'html.parser')

    if name == "voice":
        # Check voice page selectors
        selectors = [
            '.voice-container',
            '.voice-container.ba-new-backdrop-filter',
            '.list-group',
            '.list-group-item',
            '.dub-item',
            '.dub-content',
            '.dub-content .list',
            '.dub-name',
            '.dub-txt',
            'audio',
            'audio[src]',
            '.item-header-title',
            '.voice-header',
            '.voice-header .avatar img',
            '.voice-header .name',
            '.ba-tj-container',
            '.left-container',
            '.container-box',
            '[class*=voice]',
            '[class*=dub]',
        ]
        for sel in selectors:
            found = soup.select(sel)
            print(f"  {sel}: {len(found)} matches")
            if found and len(found) > 0:
                print(f"    First: {str(found[0])[:300]}")

        # Check audio src URLs
        audios = soup.select('audio[src]')
        print(f"\n  Audio sources ({len(audios)}):")
        for i, a in enumerate(audios[:5]):
            src = a.get('src', '')
            aid = a.get('id', '')
            print(f"    [{i}] id={aid} src={src[:120]}")

        # Check voice container structure
        vc = soup.select('.voice-container')
        if vc:
            print(f"\n  Voice container structure (first):")
            print(f"    {str(vc[0])[:500]}")

        # Check list-group items
        lg = soup.select('.list-group')
        if lg:
            print(f"\n  List groups ({len(lg)}):")
            for i, g in enumerate(lg[:3]):
                gid = g.get('id', '')
                items = g.select('.list-group-item')
                print(f"    [{i}] id={gid} items={len(items)}")
                if items:
                    print(f"      First item: {str(items[0])[:400]}")

    elif name == "gallery":
        # Check gallery page selectors
        selectors = [
            '.header-container',
            '.header-container#gfjs',
            '.header-container#hydt',
            '.header-container#sdj-jp',
            '.header-container#sdj-cn',
            '.header-container#yhs',
            '.header-container#gfys',
            '.header-container#emoji',
            '.header-container#video',
            'img',
            'img[src]',
            'img[data-src]',
            '.sdj-text',
            '.video-list-item',
            'video',
            'video[src]',
            '.voice-header .name',
            '.ba-tj-container',
            '[class*=header-container]',
        ]
        for sel in selectors:
            found = soup.select(sel)
            print(f"  {sel}: {len(found)} matches")
            if found and len(found) > 0:
                print(f"    First: {str(found[0])[:300]}")

        # Check all images
        all_imgs = soup.select('img')
        print(f"\n  All images ({len(all_imgs)}):")
        for i, img in enumerate(all_imgs[:10]):
            src = img.get('src', '')
            data_src = img.get('data-src', '')
            alt = img.get('alt', '')
            cls = img.get('class', '')
            if not src.startswith('data:'):
                print(f"    [{i}] class={cls} alt={alt[:30]} src={src[:100]}")

        # Check header-containers
        containers = soup.select('.header-container')
        print(f"\n  Header containers ({len(containers)}):")
        for c in containers:
            cid = c.get('id', '')
            imgs = c.select('img')
            videos = c.select('video')
            print(f"    id={cid} imgs={len(imgs)} videos={len(videos)}")

async def main():
    from playwright.async_api import async_playwright
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page(
            user_agent='Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36'
        )

        # Analyze voice page
        await analyze_page(page, 'https://www.gamekee.com/ba/tj/59934.html?tab=2', "voice")

        # Analyze gallery page
        await analyze_page(page, 'https://www.gamekee.com/ba/tj/59934.html?tab=3', "gallery")

        await browser.close()

asyncio.run(main())
