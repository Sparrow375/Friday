"""Test different user agents against Google to find one that returns parseable HTML."""
import urllib.request, urllib.parse, json, re

query = "capital of france"
encoded = urllib.parse.quote(query)

tests = [
    ("Googlebot", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"),
    ("curl", "curl/7.68.0"),
    ("Lynx", "Lynx/2.9.0dev.5 libwww-FM/2.14"),
    ("Wget", "Wget/1.21"),
    ("Old Chrome", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.93 Safari/537.36"),
    ("Android Samsung", "Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S926B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/24.0 Chrome/122.0.0.0 Mobile Safari/537.36"),
]

for name, ua in tests:
    try:
        url = f"https://www.google.com/search?q={encoded}&hl=en"
        req = urllib.request.Request(url, headers={
            "User-Agent": ua,
            "Accept-Language": "en-US,en;q=0.9",
            "Accept": "text/html",
        })
        with urllib.request.urlopen(req, timeout=5) as r:
            html = r.read().decode("utf-8", errors="ignore")
            has_paris = "Paris" in html
            size = len(html)
            # Count classes
            class_count = len(re.findall(r'class="', html))
            print(f"[{name}] size={size}, Paris={has_paris}, classes={class_count}")
            if has_paris:
                # Try to extract context around Paris
                for m in re.finditer(r"Paris", html):
                    start = max(0, m.start() - 100)
                    end = min(len(html), m.end() + 200)
                    ctx = re.sub(r"<[^>]+>", " ", html[start:end]).strip()
                    ctx = re.sub(r"\s+", " ", ctx)
                    if len(ctx) > 20:
                        print(f"  Context: {ctx[:300]}")
                        break
    except Exception as e:
        print(f"[{name}] ERROR - {e}")

print()
print("=" * 60)
print("Testing google.com/search with 'nfpr' param (no JS redirect):")
print("=" * 60)

for name, ua in [("curl", "curl/7.68.0"), ("Wget", "Wget/1.21")]:
    try:
        url = f"https://www.google.com/search?q={encoded}&hl=en&nfpr=1&gl=us"
        req = urllib.request.Request(url, headers={
            "User-Agent": ua,
            "Accept": "text/html",
        })
        with urllib.request.urlopen(req, timeout=5) as r:
            html = r.read().decode("utf-8", errors="ignore")
            has_paris = "Paris" in html
            size = len(html)
            print(f"[{name}+nfpr] size={size}, Paris={has_paris}")
            if has_paris:
                for m in re.finditer(r"Paris", html):
                    start = max(0, m.start() - 100)
                    end = min(len(html), m.end() + 200)
                    ctx = re.sub(r"<[^>]+>", " ", html[start:end]).strip()
                    ctx = re.sub(r"\s+", " ", ctx)
                    if len(ctx) > 20:
                        print(f"  Context: {ctx[:300]}")
                        break
    except Exception as e:
        print(f"[{name}+nfpr] ERROR - {e}")

# Also try Google's API alternative: Google Knowledge Graph Search API (free tier)
# and Google Custom Search JSON API
print()
print("=" * 60) 
print("Testing alternative APIs:")
print("=" * 60)

# Try DuckDuckGo HTML (not lite) with a specific query
try:
    url = f"https://html.duckduckgo.com/html/?q={encoded}"
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    })
    with urllib.request.urlopen(req, timeout=5) as r:
        html = r.read().decode("utf-8", errors="ignore")
        has_paris = "Paris" in html
        size = len(html)
        snippets = re.findall(r'class="result__snippet"[^>]*>(.*?)</a>', html, re.S)
        print(f"[DDG HTML] size={size}, Paris={has_paris}, snippets={len(snippets)}")
        if snippets:
            for s in snippets[:3]:
                clean = re.sub(r"<[^>]+>", "", s).strip()
                clean = re.sub(r"\s+", " ", clean)
                print(f"  Snippet: {clean[:200]}")
except Exception as e:
    print(f"[DDG HTML] ERROR - {e}")
