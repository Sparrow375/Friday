package com.friday.assistant.intelligence.brief

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ParsedArticle(
    val title: String,
    val snippet: String,
    val url: String,
    val sourceName: String,
    val pubDate: Long
)

object BriefCrawler {
    private const val TAG = "BriefCrawler"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    )

    private fun getRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", userAgents.random())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()
    }

    fun fetchAndParseRss(url: String, sourceName: String): List<ParsedArticle> {
        val articles = mutableListOf<ParsedArticle>()
        try {
            Log.d(TAG, "Fetching RSS feed: $url")
            val request = getRequest(url)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to fetch RSS: $url (code ${response.code})")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                articles.addAll(parseRssXml(body, sourceName, url))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching/parsing RSS: $url", e)
        }
        return articles
    }

    fun scrapeHtml(url: String, keywords: List<String>, sourceName: String): List<ParsedArticle> {
        val articles = mutableListOf<ParsedArticle>()
        try {
            Log.d(TAG, "Scraping HTML webpage: $url")
            val request = getRequest(url)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to fetch HTML: $url (code ${response.code})")
                    return emptyList()
                }
                val html = response.body?.string() ?: return emptyList()
                val doc = Jsoup.parse(html, url)
                
                if (url.contains("devpost.com")) {
                    articles.addAll(scrapeDevpost(doc, sourceName))
                } else {
                    articles.addAll(scrapeGenericHtml(doc, keywords, sourceName))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping HTML: $url", e)
        }
        return articles
    }

    private fun parseRssXml(xml: String, sourceName: String, sourceUrl: String): List<ParsedArticle> {
        val list = mutableListOf<ParsedArticle>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var inItem = false

            var title = ""
            var link = ""
            var description = ""
            var pubDateStr = ""

            val pubDateFormats = listOf(
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US),
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            ).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name.equals("item", ignoreCase = true) || name.equals("entry", ignoreCase = true)) {
                            inItem = true
                            title = ""
                            link = ""
                            description = ""
                            pubDateStr = ""
                        } else if (inItem) {
                            when {
                                name.equals("title", ignoreCase = true) -> title = parser.nextText().trim()
                                name.equals("link", ignoreCase = true) -> {
                                    val rel = parser.getAttributeValue(null, "rel")
                                    if (rel == "alternate") {
                                        link = parser.getAttributeValue(null, "href") ?: ""
                                    } else {
                                        val nextText = parser.nextText().trim()
                                        if (nextText.isNotEmpty()) {
                                            link = nextText
                                        } else {
                                            link = parser.getAttributeValue(null, "href") ?: ""
                                        }
                                    }
                                }
                                name.equals("description", ignoreCase = true) || name.equals("summary", ignoreCase = true) || name.equals("content", ignoreCase = true) -> {
                                    val rawDesc = parser.nextText().trim()
                                    // Strip HTML markup using Jsoup
                                    description = Jsoup.parse(rawDesc).text()
                                }
                                name.equals("pubDate", ignoreCase = true) || name.equals("published", ignoreCase = true) || name.equals("updated", ignoreCase = true) -> {
                                    pubDateStr = parser.nextText().trim()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name.equals("item", ignoreCase = true) || name.equals("entry", ignoreCase = true)) {
                            inItem = false
                            if (title.isNotEmpty()) {
                                var parsedTime = System.currentTimeMillis()
                                if (pubDateStr.isNotEmpty()) {
                                    for (format in pubDateFormats) {
                                        try {
                                            val date = format.parse(pubDateStr)
                                            if (date != null) {
                                                parsedTime = date.time
                                                break
                                            }
                                        } catch (e: Exception) {
                                            // try next format
                                        }
                                    }
                                }
                                if (link.isEmpty()) link = sourceUrl
                                // Truncate description/snippet to ~400 chars to avoid memory bloat
                                val cleanSnippet = if (description.length > 400) description.substring(0, 397) + "..." else description
                                list.add(ParsedArticle(title, cleanSnippet, link, sourceName, parsedTime))
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "XML parse error", e)
        }
        return list
    }

    private fun scrapeDevpost(doc: Document, sourceName: String): List<ParsedArticle> {
        val list = mutableListOf<ParsedArticle>()
        // Devpost hackathon card containers: .hackathon-tile or similar
        val tiles = doc.select(".hackathon-tile, .challenge-listing, .featured-challenge")
        for (tile in tiles) {
            val titleEl = tile.select(".title, h3, h4, .challenge-title").first() ?: continue
            val title = titleEl.text().trim()
            val linkEl = tile.select("a").first() ?: continue
            val link = linkEl.absUrl("href")
            val descEl = tile.select(".tagline, .description, .challenge-description, p").first()
            val desc = descEl?.text()?.trim() ?: "No description provided."
            list.add(ParsedArticle(title, desc, link, sourceName, System.currentTimeMillis()))
        }
        
        // Fallback: collect all links pointing to /challenges/
        if (list.isEmpty()) {
            val challengeLinks = doc.select("a[href*=/challenges/]")
            for (link in challengeLinks) {
                val title = link.text().trim()
                if (title.length > 5) {
                    val url = link.absUrl("href")
                    list.add(ParsedArticle(title, "Hackathon event found at Devpost.", url, sourceName, System.currentTimeMillis()))
                }
            }
        }
        return list.distinctBy { it.url }
    }

    private fun scrapeGenericHtml(doc: Document, keywords: List<String>, sourceName: String): List<ParsedArticle> {
        val list = mutableListOf<ParsedArticle>()
        // Search for article blocks or links containing keywords
        val links = doc.select("a")
        for (link in links) {
            val text = link.text().trim()
            val href = link.absUrl("href")
            if (href.isNotEmpty() && text.length > 10) {
                val matchesKeyword = keywords.any { text.lowercase().contains(it.lowercase()) }
                if (matchesKeyword) {
                    // Try to find surrounding paragraph or text as snippet
                    var parentText = link.parent()?.text() ?: ""
                    if (parentText.length < 50) {
                        parentText = link.parent()?.parent()?.text() ?: ""
                    }
                    val snippet = if (parentText.length > 300) parentText.substring(0, 297) + "..." else parentText
                    list.add(ParsedArticle(text, snippet, href, sourceName, System.currentTimeMillis()))
                }
            }
        }
        return list.distinctBy { it.url }.take(10) // Cap generic HTML scraping to prevent spam
    }
}
