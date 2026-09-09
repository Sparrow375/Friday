import urllib.request, urllib.parse, json, re

def search_answer(query):
    # Tier 1: DuckDuckGo Instant Answer API
    try:
        url = f'https://api.duckduckgo.com/?q={urllib.parse.quote(query)}&format=json&no_html=1&skip_disambig=1'
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=3) as r:
            data = json.loads(r.read())
            ans = data.get('Answer') or data.get('AbstractText')
            if ans: return f'[DDG Instant] {ans}'
            topics = data.get('RelatedTopics', [])
            if topics and isinstance(topics[0], dict) and topics[0].get('Text'):
                text = topics[0].get('Text')
                if len(text) > 20: return f'[DDG Topic] {text}'
    except Exception: pass

    # Tier 2: Wikipedia Search & Summary API
    try:
        # First query Wikipedia Search API to get top matching page title
        clean = re.sub(r'^(what is|who is|where is|when is|how is|define|tell me about)\s+', '', query, flags=re.I).strip()
        search_url = f'https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch={urllib.parse.quote(clean)}&utf8=&format=json&srlimit=1'
        req = urllib.request.Request(search_url, headers={'User-Agent': 'FridayAssistant/1.0 (contact@friday.ai)'})
        with urllib.request.urlopen(req, timeout=3) as r:
            sdata = json.loads(r.read())
            search_results = sdata.get('query', {}).get('search', [])
            if search_results:
                top_title = search_results[0]['title']
                snippet = re.sub(r'<[^>]+>', '', search_results[0].get('snippet', '')).strip()
                # Now fetch summary of top page
                summary_url = f'https://en.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(top_title)}'
                sreq = urllib.request.Request(summary_url, headers={'User-Agent': 'FridayAssistant/1.0 (contact@friday.ai)'})
                with urllib.request.urlopen(sreq, timeout=3) as sr:
                    sum_data = json.loads(sr.read())
                    ext = sum_data.get('extract')
                    if ext:
                        # Extract first 1-2 sentences
                        sentences = re.split(r'(?<=[.!?])\s+', ext)
                        res = ' '.join(sentences[:2])
                        return f'[Wikipedia ({top_title})] {res}'
                if snippet:
                    return f'[Wikipedia Snippet] {snippet}'
    except Exception as e:
        # print('Wiki error:', e)
        pass

    # Tier 3: DuckDuckGo Lite Search
    try:
        url = 'https://lite.duckduckgo.com/lite/'
        post_data = urllib.parse.urlencode({'q': query}).encode('utf-8')
        req = urllib.request.Request(url, data=post_data, headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Content-Type': 'application/x-www-form-urlencoded'
        })
        with urllib.request.urlopen(req, timeout=4) as r:
            html = r.read().decode('utf-8', errors='ignore')
            snippets = re.findall(r'<td class=[\'"]result-snippet[\'"]>(.*?)</td>', html, re.S)
            if snippets:
                clean_s = re.sub(r'<[^>]+>', '', snippets[0]).strip()
                clean_s = re.sub(r'\s+', ' ', clean_s)
                if len(clean_s) > 15:
                    return f'[DDG Lite] {clean_s}'
    except Exception as e:
        # print('DDG Lite error:', e)
        pass

    return None

test_queries = [
    'capital of france',
    'who is elon musk',
    'what is photosynthesis',
    'define gravity',
    'score of india vs australia',
    'how tall is mount everest',
    'why is the sky blue'
]

for q in test_queries:
    print(f'Query: {q}')
    print(f'Answer: {search_answer(q)}')
    print()
