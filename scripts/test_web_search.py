"""
Test harness for Friday's web search pipeline (Google-first architecture).
Mirrors the Kotlin WebSearchTool pipeline: Google Snippet → DDG Instant → Browser Fallback.
"""
import urllib.request, urllib.parse, json, re, time

def clean_answer_text(raw):
    """Clean HTML entities and limit to 3 sentences."""
    text = re.sub(r'<[^>]+>', '', raw)
    text = text.replace('&quot;', '"').replace('&amp;', '&').replace('&#39;', "'").replace('&nbsp;', ' ')
    text = text.replace('&lt;', '<').replace('&gt;', '>')
    text = re.sub(r'\[[0-9a-zA-Z_\s-]+\]', '', text)  # Remove citations
    text = re.sub(r'\s+', ' ', text).strip()
    sentences = re.split(r'(?<=[.!?])\s+', text)
    if len(sentences) > 3:
        return ' '.join(sentences[:3])
    return text


def scrape_google_answer(query):
    """Tier 1: Scrape Google search page for featured snippets and knowledge panels."""
    try:
        encoded = urllib.parse.quote(query)
        url = f'https://www.google.com/search?q={encoded}&hl=en'
        req = urllib.request.Request(url, headers={
            'User-Agent': 'Mozilla/5.0 (Linux; Android 14; SM-S926B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36',
            'Accept-Language': 'en-US,en;q=0.9',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        })
        with urllib.request.urlopen(req, timeout=5) as r:
            html = r.read().decode('utf-8', errors='ignore')

            # Check for CAPTCHA
            if 'detected unusual traffic' in html or 'consent.google.com' in html:
                return None

            # Strategy 1: Featured snippet (class="hgKElc" or "IZ6rdc")
            m = re.search(r'class="hgKElc"[^>]*>(.*?)</(?:span|div)', html, re.S)
            if m:
                text = clean_answer_text(m.group(1))
                if len(text) > 15:
                    return f'[Google Featured] {text}'

            # Strategy 2: Knowledge panel (class="kno-rdesc")
            m = re.search(r'class="kno-rdesc"[^>]*>.*?<span[^>]*>(.*?)</span>', html, re.S)
            if m:
                text = clean_answer_text(m.group(1))
                if len(text) > 15:
                    return f'[Google Knowledge] {text}'

            # Strategy 3: Calculator / converter (class="qv3Wpe", "Z0LcW", "XcVN5d")
            m = re.search(r'class="(?:qv3Wpe|Z0LcW|XcVN5d)"[^>]*>(.*?)</', html, re.S)
            if m:
                text = clean_answer_text(m.group(1))
                if len(text) > 2:
                    return f'[Google Direct] {text}'

            # Strategy 4: Knowledge fact box ("wDYxhc")
            m = re.search(r'data-attrid="[^"]*"[^>]*class="[^"]*wDYxhc[^"]*"[^>]*>(.*?)</div>', html, re.S)
            if m:
                text = clean_answer_text(m.group(1))
                if len(text) > 20 and 'People also ask' not in text:
                    return f'[Google Fact] {text}'

            # Strategy 5: BNeawe text snippets (mobile search results)
            bneawe_matches = re.findall(r'class="BNeawe[^"]*"[^>]*>(.*?)</div>', html, re.S)
            for snippet in bneawe_matches:
                text = clean_answer_text(snippet)
                if (len(text) > 40
                    and not text.startswith('http')
                    and 'Google' not in text
                    and 'Sign in' not in text
                    and 'Search tools' not in text
                    and not re.match(r'^[A-Z][a-z]{2} \d{1,2}, \d{4}', text)):
                    return f'[Google BNeawe] {text}'

            # Strategy 6: Organic result snippet (class="VwiC3b" or "lEBKkf")
            m = re.search(r'class="(?:VwiC3b|lEBKkf)[^"]*"[^>]*>(.*?)</(?:span|div)', html, re.S)
            if m:
                text = clean_answer_text(m.group(1))
                if len(text) > 30:
                    return f'[Google Organic] {text}'

            return None
    except Exception as e:
        return None


def search_ddg_instant(query):
    """Tier 2: DuckDuckGo Instant Answer API (good for calculators, conversions)."""
    try:
        url = f'https://api.duckduckgo.com/?q={urllib.parse.quote(query)}&format=json&no_html=1&skip_disambig=1'
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=3) as r:
            data = json.loads(r.read())
            ans = data.get('Answer', '') or ''
            if ans:
                return f'[DDG Instant] {ans}'
            abstract = data.get('AbstractText', '') or ''
            if len(abstract) > 30:
                return f'[DDG Abstract] {clean_answer_text(abstract)}'
    except Exception:
        pass
    return None


def search_ddg_lite(query):
    """Tier 3: DuckDuckGo Lite search snippet (recipes, instructions, definitions)."""
    try:
        url = 'https://lite.duckduckgo.com/lite/'
        post_data = urllib.parse.urlencode({'q': query}).encode('utf-8')
        req = urllib.request.Request(url, data=post_data, headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Content-Type': 'application/x-www-form-urlencoded',
        })
        with urllib.request.urlopen(req, timeout=4) as r:
            html = r.read().decode('utf-8', errors='ignore')
            snippets = re.findall(r'<td class=[\'"]result-snippet[\'"]>(.*?)</td>', html, re.S)
            if snippets:
                clean_s = clean_answer_text(snippets[0])
                if len(clean_s) > 20 and not clean_s.startswith('http'):
                    return f'[DDG Lite] {clean_s}'
    except Exception:
        pass
    return None


def search_answer(query):
    """Full 4-tier search pipeline matching the Android WebSearchTool."""
    # Tier 1: Google Featured Snippet
    result = scrape_google_answer(query)
    if result:
        return result

    # Tier 2: DDG Instant
    result = search_ddg_instant(query)
    if result:
        return result

    # Tier 3: DDG Lite Snippet
    result = search_ddg_lite(query)
    if result:
        return result

    # Tier 4: Browser fallback
    return f"[Browser Fallback] Would open Google for '{query}'"


# ========================================
# TEST SUITE
# ========================================

test_queries = [
    # General knowledge (should get direct answers from Google)
    'capital of france',
    'who is elon musk',
    'what is photosynthesis',
    'define gravity',
    'how tall is mount everest',
    'why is the sky blue',
    'how to make pizza',

    # Explicit search queries (in the real app, these open Google directly — 
    # they should NOT be hitting this pipeline at all)
    'icc',
    'real madrid',
    'score of india vs australia',

    # Calculator / conversion
    '15 USD to INR',
    'what is 25 celsius in fahrenheit',
]

print("=" * 70)
print("FRIDAY WEB SEARCH PIPELINE TEST (Google-First Architecture)")
print("=" * 70)
print()

passed = 0
total = len(test_queries)

for q in test_queries:
    start = time.time()
    result = search_answer(q)
    elapsed = time.time() - start
    
    is_browser_fallback = result and '[Browser Fallback]' in result
    status = 'PASS' if (result and not is_browser_fallback) else 'FALLBACK'
    if not is_browser_fallback:
        passed += 1
    
    print(f'[{status}] ({elapsed:.2f}s) Query: "{q}"')
    print(f'  Answer: {result}')
    print()

print("=" * 70)
print(f"Results: {passed}/{total} queries got direct answers ({100*passed/total:.0f}%)")
print(f"Remaining {total-passed} queries would fall through to browser")
print("=" * 70)
