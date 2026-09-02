import urllib.request
import urllib.parse
import gzip
import re
import html
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

user_agents = {
    "desktop_chrome": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
    "android_chrome": "Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
    "iphone_safari": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
    "android_google_app": "Mozilla/5.0 (Linux; Android 14; SM-S921B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.0.0 Mobile Safari/537.36 GSA/15.34.41.28.arm64"
}

def test_google_scraping(query, ua_name, ua_string):
    encoded_q = urllib.parse.quote(query)
    url = f"https://www.google.com/search?q={encoded_q}&hl=en&gl=us"
    headers = {
        'User-Agent': ua_string,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
        'Accept-Encoding': 'gzip, deflate',
        'Sec-Fetch-Dest': 'document',
        'Sec-Fetch-Mode': 'navigate',
        'Sec-Fetch-Site': 'none',
        'Sec-Fetch-User': '?1',
        'Upgrade-Insecure-Requests': '1',
        'Cache-Control': 'max-age=0'
    }
    
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=6, context=ctx) as resp:
            raw_data = resp.read()
            if resp.info().get('Content-Encoding') == 'gzip':
                content = gzip.decompress(raw_data).decode('utf-8', errors='ignore')
            else:
                content = raw_data.decode('utf-8', errors='ignore')
                
            print(f"[{ua_name}] Query: '{query}' -> Status: {resp.status}, Content Length: {len(content)}")
            
            # Check if it's the enablejs challenge
            if "enablejs" in content:
                print(f"  -> Returned JS challenge/redirect page ({len(content)} bytes)")
                return None
                
            # Check for AI Overview / SGE (Search Generative Experience)
            # AI overview classes / containers:
            # - data-attrid="wa:/description"
            # - class="MjjYud"
            # - class="X5tZ2e" (AI overview header)
            # - class="wDYxhc" (AI overview content)
            # - class="V3FYCf" (Generative text)
            # - class="hgKElc" (Featured snippet / AI summary)
            # - class="Z0LcW" (Direct bold answer)
            # - class="kno-rdesc" (Knowledge panel)
            # - class="ILfuVd" (Direct answer span)
            # - class="webanswers-webanswers_table__webanswers-table"
            
            ai_patterns = [
                # AI Overview Generative Box
                (r'data-attrid="wa:[^"]*"[^>]*>(.*?)</div>', "wa:description"),
                (r'class="X5tZ2e[^"]*"[^>]*>(.*?)</div>', "X5tZ2e (AI Box)"),
                (r'class="wDYxhc[^"]*"[^>]*>(.*?)</div>', "wDYxhc (AI Overview)"),
                (r'class="hgKElc"[^>]*>(.*?)</div>', "hgKElc (Featured Snippet)"),
                (r'class="Z0LcW[^"]*"[^>]*>(.*?)</div>', "Z0LcW (Bold Direct Answer)"),
                (r'class="ILfuVd"[^>]*>(.*?)</span>', "ILfuVd (Direct Answer)"),
                (r'class="kno-rdesc"[^>]*>.*?<span>(.*?)</span>', "kno-rdesc (Knowledge Panel)"),
                (r'class="V3FYCf"[^>]*>(.*?)</div>', "V3FYCf (Quick Answer)"),
                (r'class="BNeawe iBp4i AP7Wnd"[^>]*>(.*?)</div>', "BNeawe iBp4i (Mobile Bold)"),
                (r'class="BNeawe s3v9rd AP7Wnd"[^>]*>(.*?)</div>', "BNeawe s3v9rd (Mobile Snippet)"),
            ]
            
            found = False
            for pat, name in ai_patterns:
                m = re.search(pat, content, re.DOTALL)
                if m:
                    raw = m.group(1)
                    clean = re.sub(r'<[^>]+>', ' ', raw)
                    clean = html.unescape(clean).strip()
                    clean = re.sub(r'\s+', ' ', clean)
                    if len(clean) > 10:
                        print(f"  -> Found via [{name}]: {clean[:160]}...")
                        found = True
                        break
            if not found:
                # Let's see if there are any search result snippets at all
                snippets = re.findall(r'<div class="VwiC3b[^"]*"[^>]*>(.*?)</div>', content)
                if snippets:
                    c = html.unescape(re.sub(r'<[^>]+>', ' ', snippets[0])).strip()
                    c = re.sub(r'\s+', ' ', c)
                    print(f"  -> Organic top snippet: {c[:160]}...")
                else:
                    print("  -> No recognizable snippet container found in HTML.")
    except Exception as e:
        print(f"[{ua_name}] Error: {e}")

if __name__ == "__main__":
    queries = [
        "capital of france",
        "why is the sky blue",
        "who is the prime minister of india",
        "how does photosynthesis work"
    ]
    for q in queries:
        print("="*60)
        for ua_name, ua_str in user_agents.items():
            test_google_scraping(q, ua_name, ua_str)
