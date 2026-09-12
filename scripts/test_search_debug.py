"""Debug what HTML classes Google actually returns for a search query."""
import urllib.request, urllib.parse, re

query = "capital of france"
encoded = urllib.parse.quote(query)
url = f'https://www.google.com/search?q={encoded}&hl=en'
req = urllib.request.Request(url, headers={
    'User-Agent': 'Mozilla/5.0 (Linux; Android 14; SM-S926B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36',
    'Accept-Language': 'en-US,en;q=0.9',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
})
with urllib.request.urlopen(req, timeout=5) as r:
    html = r.read().decode('utf-8', errors='ignore')

# Save full HTML for analysis
with open('google_debug.html', 'w', encoding='utf-8') as f:
    f.write(html)

print(f"HTML size: {len(html)} bytes")
print(f"Contains 'Paris': {'Paris' in html}")
print(f"Contains 'hgKElc': {'hgKElc' in html}")
print(f"Contains 'kno-rdesc': {'kno-rdesc' in html}")
print(f"Contains 'BNeawe': {'BNeawe' in html}")
print(f"Contains 'VwiC3b': {'VwiC3b' in html}")
print(f"Contains 'lEBKkf': {'lEBKkf' in html}")
print(f"Contains 'wDYxhc': {'wDYxhc' in html}")
print(f"Contains 'Z0LcW': {'Z0LcW' in html}")

# Find all unique class names that contain substantial text
classes = set(re.findall(r'class="([^"]{3,40})"', html))
print(f"\nTotal unique classes: {len(classes)}")

# Find text blocks near 'Paris'
idx = html.find('Paris')
if idx > 0:
    # Show 500 chars around each 'Paris' occurrence
    for m in re.finditer(r'Paris', html):
        start = max(0, m.start() - 200)
        end = min(len(html), m.end() + 200)
        context = html[start:end]
        # Find containing class
        class_match = re.search(r'class="([^"]+)"[^>]*>[^<]*Paris', html[max(0,m.start()-500):m.end()+100])
        if class_match:
            print(f"\n--- Paris context (class={class_match.group(1)}) ---")
            # Just show the class and the text content
            text_around = re.sub(r'<[^>]+>', ' ', context).strip()
            text_around = re.sub(r'\s+', ' ', text_around)
            if len(text_around) > 20:
                print(text_around[:300])
