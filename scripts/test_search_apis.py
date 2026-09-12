"""Test DuckDuckGo HTML search and other working alternatives."""
import urllib.request, urllib.parse, re, json

def test_ddg_html(query):
    """Test DuckDuckGo full HTML search."""
    try:
        url = "https://html.duckduckgo.com/html/?q=" + urllib.parse.quote(query)
        req = urllib.request.Request(url, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        })
        with urllib.request.urlopen(req, timeout=5) as r:
            html = r.read().decode("utf-8", errors="ignore")
            
            # Try various snippet extraction patterns
            # Pattern 1: result__snippet
            snippets = re.findall(r'class="result__snippet"[^>]*>(.*?)</(?:a|td)', html, re.S)
            if snippets:
                for s in snippets[:3]:
                    clean = re.sub(r"<[^>]+>", "", s).strip()
                    clean = re.sub(r"\s+", " ", clean)
                    if len(clean) > 15:
                        return clean
            
            # Pattern 2: result-snippet (DDG Lite format embedded)
            snippets = re.findall(r'class="result-snippet"[^>]*>(.*?)</td>', html, re.S)
            if snippets:
                clean = re.sub(r"<[^>]+>", "", snippets[0]).strip()
                clean = re.sub(r"\s+", " ", clean)
                if len(clean) > 15:
                    return clean
            
            # Pattern 3: Any td with substantial text after a result link
            tds = re.findall(r"<td[^>]*>(.*?)</td>", html, re.S)
            for td in tds:
                clean = re.sub(r"<[^>]+>", "", td).strip()
                clean = re.sub(r"\s+", " ", clean)
                if len(clean) > 40 and not clean.startswith("http"):
                    return clean
                    
    except Exception as e:
        print(f"  DDG HTML error: {e}")
    return None

def test_ddg_lite(query):
    """Test DuckDuckGo Lite search."""
    try:
        url = "https://lite.duckduckgo.com/lite/"
        post_data = urllib.parse.urlencode({"q": query}).encode("utf-8")
        req = urllib.request.Request(url, data=post_data, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Content-Type": "application/x-www-form-urlencoded",
        })
        with urllib.request.urlopen(req, timeout=5) as r:
            html = r.read().decode("utf-8", errors="ignore")
            snippets = re.findall(r"class=['\"]result-snippet['\"]>(.*?)</td>", html, re.S)
            if snippets:
                clean = re.sub(r"<[^>]+>", "", snippets[0]).strip()
                clean = re.sub(r"\s+", " ", clean)
                if len(clean) > 15:
                    return clean
    except Exception as e:
        print(f"  DDG Lite error: {e}")
    return None

def test_wiki_summary(query):
    """Test Wikipedia summary."""
    try:
        clean_q = re.sub(r"^(what is|who is|where is|define|tell me about|how to)\s+", "", query, flags=re.I).strip()
        search_url = f"https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch={urllib.parse.quote(clean_q)}&utf8=&format=json&srlimit=1"
        req = urllib.request.Request(search_url, headers={"User-Agent": "FridayAssistant/1.0"})
        with urllib.request.urlopen(req, timeout=3) as r:
            data = json.loads(r.read())
            results = data.get("query", {}).get("search", [])
            if results:
                title = results[0]["title"]
                summary_url = f"https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(title)}"
                sreq = urllib.request.Request(summary_url, headers={"User-Agent": "FridayAssistant/1.0"})
                with urllib.request.urlopen(sreq, timeout=3) as sr:
                    sum_data = json.loads(sr.read())
                    ext = sum_data.get("extract", "")
                    sentences = re.split(r"(?<=[.!?])\s+", ext)
                    return " ".join(sentences[:2])
    except Exception as e:
        print(f"  Wiki error: {e}")
    return None


queries = [
    "capital of france",
    "who is elon musk", 
    "how to make pizza",
    "define gravity",
    "how tall is mount everest",
    "why is the sky blue",
    "icc",
    "real madrid",
    "score of india vs australia",
    "15 USD to INR",
]

print("=" * 70)
print("TESTING ANSWER SOURCES")
print("=" * 70)

for q in queries:
    print(f"\nQuery: \"{q}\"")
    
    ddg_html = test_ddg_html(q)
    print(f"  DDG HTML: {ddg_html[:150] if ddg_html else 'EMPTY'}")
    
    ddg_lite = test_ddg_lite(q)
    print(f"  DDG Lite: {ddg_lite[:150] if ddg_lite else 'EMPTY'}")
    
    wiki = test_wiki_summary(q)
    print(f"  Wikipedia: {wiki[:150] if wiki else 'EMPTY'}")
