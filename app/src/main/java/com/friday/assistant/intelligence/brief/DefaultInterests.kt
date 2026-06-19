package com.friday.assistant.intelligence.brief

import android.util.Log
import com.friday.assistant.core.db.FridayDao
import com.friday.assistant.core.db.InterestEntity
import com.google.gson.Gson

object DefaultInterests {
    private const val TAG = "DefaultInterests"

    private val defaultList = listOf(
        InterestEntity(
            id = "cricket_intl",
            title = "International Cricket",
            category = "sports",
            keywordsJson = Gson().toJson(listOf(
                "cricket", "test match", "t20", "t20 world cup", "ipl", 
                "cricinfo", "odi", "kohli", "dhoni", "rohit", "match review"
            )),
            sourcesJson = Gson().toJson(listOf(
                "https://www.espncricinfo.com/rss/content/story/feeds/index.xml",
                "https://news.google.com/rss/search?q=international+cricket&hl=en-IN&gl=IN&ceid=IN:en"
            )),
            isEnabled = true,
            isCustom = false
        ),
        InterestEntity(
            id = "ai_tech",
            title = "AI Tech",
            category = "news",
            keywordsJson = Gson().toJson(listOf(
                "artificial intelligence", "large language models", "machine learning", 
                "deep learning", "generative ai", "qwen", "llama", "chatgpt", "openai", "claude", "gpu"
            )),
            sourcesJson = Gson().toJson(listOf(
                "https://techcrunch.com/category/artificial-intelligence/feed/",
                "https://news.google.com/rss/search?q=large+language+models&hl=en-US&gl=US&ceid=US:en"
            )),
            isEnabled = true,
            isCustom = false
        ),
        InterestEntity(
            id = "hackathons",
            title = "Hackathons",
            category = "event",
            keywordsJson = Gson().toJson(listOf(
                "hackathon", "hackathons", "game jam", "devpost", 
                "mlh", "buildathon", "hackathon india", "hackathon bangalore", "major league hacking"
            )),
            sourcesJson = Gson().toJson(listOf(
                "https://devpost.com/hackathons",
                "https://news.google.com/rss/search?q=hackathons+india&hl=en-IN&gl=IN&ceid=IN:en"
            )),
            isEnabled = true,
            isCustom = false
        ),
        InterestEntity(
            id = "tech_summits",
            title = "Tech Summits",
            category = "news",
            keywordsJson = Gson().toJson(listOf(
                "google io", "apple wwdc", "microsoft build", "ces 2026", 
                "mwc", "tech summit", "tech conference", "aws re:invent"
            )),
            sourcesJson = Gson().toJson(listOf(
                "https://techcrunch.com/tag/google-io/feed/",
                "https://techcrunch.com/tag/wwdc/feed/",
                "https://news.google.com/rss/search?q=tech+conference+2026&hl=en-US&gl=US&ceid=US:en"
            )),
            isEnabled = true,
            isCustom = false
        )
    )

    suspend fun seedDefaultInterests(dao: FridayDao) {
        try {
            Log.i(TAG, "Checking and seeding default interests inside Room DB")
            for (interest in defaultList) {
                // If it already exists, do not overwrite custom enabled state/crawl time
                val existing = dao.getInterestById(interest.id)
                if (existing == null) {
                    dao.insertInterest(interest)
                    Log.d(TAG, "Seeded default interest: ${interest.title}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error seeding default interests", e)
        }
    }
}
