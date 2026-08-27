#!/usr/bin/env python3
"""
YouTube Search & Relevant Video Link Extractor
----------------------------------------------
Takes a search query and extracts the YouTube link and metadata for the most
relevant video. Also provides options to inspect top N results, output JSON,
get autoplay URLs, or open the link directly in the browser.

Usage:
    py scripts/youtube_search.py "shape of you"
    py scripts/youtube_search.py "kesariya" --url-only
    py scripts/youtube_search.py "bohemian rhapsody" --autoplay --open
    py scripts/youtube_search.py --top 5 "python tutorial"
    py scripts/youtube_search.py --test
"""

import sys
import os
import re
import json
import time
import argparse
import urllib.request
import urllib.parse
import webbrowser

# Ensure UTF-8 output on Windows consoles
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except AttributeError:
        pass

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/125.0.0.0 Safari/537.36"
)

def fetch_youtube_search_html(query: str, timeout: float = 10.0) -> str:
    """Fetches the full YouTube search results HTML for a query."""
    encoded_query = urllib.parse.quote_plus(query)
    url = f"https://www.youtube.com/results?search_query={encoded_query}"
    
    headers = {
        "User-Agent": USER_AGENT,
        "Accept-Language": "en-US,en;q=0.9",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    }
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="ignore")

def extract_videos_from_initial_data(html: str) -> list:
    """
    Extracts structured video list from ytInitialData JSON inside YouTube HTML.
    Filters out playlists, radio mixes, channel headers, and shorts shelves.
    """
    init_match = re.search(r"var ytInitialData\s*=\s*({.+?});</script>", html)
    if not init_match:
        init_match = re.search(r"ytInitialData\s*=\s*({.+?});", html)
    if not init_match:
        return []

    try:
        data = json.loads(init_match.group(1))
    except Exception:
        return []

    contents = (
        data.get("contents", {})
        .get("twoColumnSearchResultsRenderer", {})
        .get("primaryContents", {})
        .get("sectionListRenderer", {})
        .get("contents", [])
    )

    videos = []
    for section in contents:
        items = section.get("itemSectionRenderer", {}).get("contents", [])
        for item in items:
            # We strictly want videoRenderer (standard full-length video results)
            if "videoRenderer" in item:
                vr = item["videoRenderer"]
                vid = vr.get("videoId")
                if not vid:
                    continue

                # Title
                title_runs = vr.get("title", {}).get("runs", [])
                title = "".join(r.get("text", "") for r in title_runs) if title_runs else vr.get("title", {}).get("simpleText", "")

                # Channel / Owner
                owner_runs = vr.get("ownerText", {}).get("runs", [])
                channel = "".join(r.get("text", "") for r in owner_runs) if owner_runs else ""
                if not channel:
                    channel_runs = vr.get("longBylineText", {}).get("runs", [])
                    channel = "".join(r.get("text", "") for r in channel_runs) if channel_runs else ""

                # Duration
                duration = vr.get("lengthText", {}).get("simpleText", "N/A")

                # View count
                views = vr.get("viewCountText", {}).get("simpleText", "")
                if not views:
                    views_runs = vr.get("viewCountText", {}).get("runs", [])
                    views = "".join(r.get("text", "") for r in views_runs) if views_runs else "N/A"

                # Published time
                published = vr.get("publishedTimeText", {}).get("simpleText", "N/A")

                videos.append({
                    "videoId": vid,
                    "title": title,
                    "channel": channel,
                    "duration": duration,
                    "views": views,
                    "published": published,
                    "url": f"https://www.youtube.com/watch?v={vid}",
                    "autoplay_url": f"https://www.youtube.com/watch?v={vid}&autoplay=1"
                })

    return videos

def extract_videos_via_regex(html: str) -> list:
    """
    Fallback regex extraction if ytInitialData parsing encounters altered schema.
    """
    # Find all "videoRenderer":{"videoId":"..."}
    pattern = re.compile(r'"videoRenderer":\{"videoId":"([a-zA-Z0-9_-]{11})"')
    matches = pattern.findall(html)
    videos = []
    seen = set()
    for vid in matches:
        if vid not in seen:
            seen.add(vid)
            videos.append({
                "videoId": vid,
                "title": "YouTube Video",
                "channel": "Unknown",
                "duration": "N/A",
                "views": "N/A",
                "published": "N/A",
                "url": f"https://www.youtube.com/watch?v={vid}",
                "autoplay_url": f"https://www.youtube.com/watch?v={vid}&autoplay=1"
            })
    return videos

def get_top_youtube_video(query: str, timeout: float = 10.0) -> dict:
    """
    Primary API: Given a search query, returns metadata & links for the
    most relevant YouTube video.
    """
    t0 = time.time()
    html = fetch_youtube_search_html(query, timeout=timeout)
    elapsed = time.time() - t0

    videos = extract_videos_from_initial_data(html)
    method = "ytInitialData"
    if not videos:
        videos = extract_videos_via_regex(html)
        method = "regex_fallback"

    if not videos:
        # Ultimate fallback: search for /watch?v=
        watch_matches = re.findall(r"/watch\?v=([a-zA-Z0-9_-]{11})", html)
        if watch_matches:
            vid = watch_matches[0]
            videos.append({
                "videoId": vid,
                "title": "YouTube Video",
                "channel": "Unknown",
                "duration": "N/A",
                "views": "N/A",
                "published": "N/A",
                "url": f"https://www.youtube.com/watch?v={vid}",
                "autoplay_url": f"https://www.youtube.com/watch?v={vid}&autoplay=1"
            })
            method = "watch_regex_fallback"

    return {
        "query": query,
        "success": len(videos) > 0,
        "method": method,
        "elapsed_seconds": round(elapsed, 3),
        "total_results_found": len(videos),
        "top_video": videos[0] if videos else None,
        "all_videos": videos
    }

def run_self_test():
    """Runs automated verification on typical assistant queries."""
    test_queries = [
        "shape of you",
        "kesariya brahmastra",
        "bohemian rhapsody queen",
        "believer imagine dragons",
        "python tutorial for beginners",
        "mkbhd",
        "rick astley never gonna give you up"
    ]
    print("=" * 70)
    print("RUNNING YOUTUBE SEARCH EXTRACTION SELF-TEST")
    print("=" * 70)
    
    all_passed = True
    for idx, q in enumerate(test_queries, 1):
        print(f"[{idx}/{len(test_queries)}] Query: '{q}'")
        res = get_top_youtube_video(q)
        if res["success"] and res["top_video"]:
            vid = res["top_video"]
            print(f"    Status: PASS ({res['elapsed_seconds']}s, {res['method']})")
            print(f"    Title:  {vid['title']}")
            print(f"    Artist: {vid['channel']} | Duration: {vid['duration']}")
            print(f"    URL:    {vid['url']}")
        else:
            print(f"    Status: FAILED to extract video!")
            all_passed = False
        print("-" * 70)

    if all_passed:
        print("ALL TESTS PASSED SUCCESSFULLY!")
    else:
        print("SOME TESTS FAILED.")
    return all_passed

def main():
    parser = argparse.ArgumentParser(
        description="Search YouTube and return the link to the most relevant video."
    )
    parser.add_argument("query", nargs="*", help="The search query (e.g. song name, artist, topic)")
    parser.add_argument("-u", "--url-only", action="store_true", help="Print only the video URL (clean for scripts/pipes)")
    parser.add_argument("-a", "--autoplay", action="store_true", help="Print/use the autoplay URL (&autoplay=1)")
    parser.add_argument("-n", "--top", type=int, default=1, help="Number of top results to show (default: 1)")
    parser.add_argument("-j", "--json", action="store_true", help="Output results in JSON format")
    parser.add_argument("-o", "--open", action="store_true", help="Open the top result in default web browser")
    parser.add_argument("-t", "--test", action="store_true", help="Run automated test suite across sample queries")

    args = parser.parse_args()

    if args.test:
        success = run_self_test()
        sys.exit(0 if success else 1)

    query_str = " ".join(args.query).strip()

    # If no query provided via args, prompt interactively
    if not query_str:
        try:
            print("=== YouTube Video Search Tool ===")
            query_str = input("Enter YouTube search query (or press Enter to exit): ").strip()
            if not query_str:
                print("No query entered. Exiting.")
                sys.exit(0)
        except (KeyboardInterrupt, EOFError):
            print("\nExited.")
            sys.exit(0)

    res = get_top_youtube_video(query_str)

    if not res["success"] or not res["top_video"]:
        if args.json:
            print(json.dumps(res, indent=2))
        else:
            print(f"Error: No videos found for query: '{query_str}'", file=sys.stderr)
        sys.exit(1)

    top = res["top_video"]
    target_url = top["autoplay_url"] if args.autoplay else top["url"]

    if args.open:
        print(f"Opening in browser: {target_url}")
        webbrowser.open(target_url)

    if args.url_only:
        print(target_url)
        return

    if args.json:
        # Include top N in JSON
        res["all_videos"] = res["all_videos"][:args.top]
        print(json.dumps(res, indent=2))
        return

    # Formatted standard output
    print("\n" + "=" * 60)
    print(f"  Query:      {res['query']}")
    print(f"  Resolution: {res['method']} in {res['elapsed_seconds']}s")
    print("=" * 60)

    count = min(args.top, len(res["all_videos"]))
    for i in range(count):
        v = res["all_videos"][i]
        tag = "[MOST RELEVANT]" if i == 0 else f"[{i+1}]"
        print(f"\n{tag} {v['title']}")
        print(f"  Channel:  {v['channel']}")
        print(f"  Duration: {v['duration']} | Views: {v['views']} | Published: {v['published']}")
        print(f"  URL:      {v['autoplay_url'] if args.autoplay else v['url']}")

    print("\n" + "-" * 60)
    print(f"Direct Autoplay Link:\n  {top['autoplay_url']}")
    print("-" * 60 + "\n")

if __name__ == "__main__":
    main()
