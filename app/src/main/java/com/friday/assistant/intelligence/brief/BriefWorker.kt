package com.friday.assistant.intelligence.brief

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.ModelManager
import com.friday.assistant.core.db.BriefItemEntity
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BriefWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BriefWorker"
        private const val MAX_LLM_SUMMARY_PER_CRAWL = 6
        private const val RETENTION_PERIOD_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

        private val blacklistKeywords = listOf(
            "how to", "how i", "lessons learned", "tutorial", "guide", "learn ", " learning ", "tips", 
            "course", "enroll", "best of", "top 10", "top 5", "top 15", "review of", "comparison", 
            "made money", "why you should", "opinion", "advertisement", "introducing class", "my experience"
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting Daily Brief background crawl worker")
        val database = FridayApplication.database
        val dao = database.dao()

        try {
            // 1. Clean up old briefing items to prevent DB bloat
            val cutoff = System.currentTimeMillis() - RETENTION_PERIOD_MS
            dao.deleteOldBriefItems(cutoff)
            Log.d(TAG, "Database pruned for items older than 7 days")

            // 2. Fetch all enabled interests
            val interests = dao.getEnabledInterests()
            if (interests.isEmpty()) {
                Log.i(TAG, "No enabled interests registered. Idle.")
                return@withContext Result.success()
            }

            val modelManager = ModelManager(applicationContext)
            val useLlm = applicationContext.getSharedPreferences("friday_model_prefs", Context.MODE_PRIVATE)
                .getBoolean("use_llm", true)

            // Prepare LLM if available and enabled
            val isLlmAvailable = useLlm && modelManager.isLlmLoaded()
            if (isLlmAvailable && !FridayApplication.llamaEngine.isModelLoaded()) {
                val path = modelManager.getLlmModelPath()
                Log.d(TAG, "Pre-loading LlamaEngine for feed classification & summarization")
                FridayApplication.llamaEngine.loadModel(path)
            }

            var llmInvocations = 0

            for (interest in interests) {
                Log.d(TAG, "Processing interest: ${interest.title} (${interest.id})")
                val keywords: List<String> = try {
                    Gson().fromJson(interest.keywordsJson, Array<String>::class.java).toList()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse keywords for interest: ${interest.id}", e)
                    emptyList()
                }
                
                val sources: List<String> = try {
                    Gson().fromJson(interest.sourcesJson, Array<String>::class.java).toList()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse sources for interest: ${interest.id}", e)
                    emptyList()
                }

                if (keywords.isEmpty() || sources.isEmpty()) continue

                val crawledArticles = mutableListOf<ParsedArticle>()

                // Crawl feeds
                for (sourceUrl in sources) {
                    try {
                        val items = if (sourceUrl.endsWith(".xml") || sourceUrl.endsWith(".rss") || 
                                       sourceUrl.contains("/rss") || sourceUrl.contains("feed") || 
                                       sourceUrl.contains("google.com/rss")) {
                            BriefCrawler.fetchAndParseRss(sourceUrl, interest.title)
                        } else {
                            BriefCrawler.scrapeHtml(sourceUrl, keywords, interest.title)
                        }
                        crawledArticles.addAll(items)
                        // Be polite, wait a short moment
                        kotlinx.coroutines.delay(1000)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to crawl source: $sourceUrl", e)
                    }
                }

                // Stage 1 Filtering: Substring keyword match (zero CPU overhead)
                val candidateArticles = crawledArticles.filter { article ->
                    val matchesKeyword = keywords.any { kw ->
                        article.title.contains(kw, ignoreCase = true) || 
                        article.snippet.contains(kw, ignoreCase = true)
                    }
                    if (!matchesKeyword) return@filter false

                    val titleLower = article.title.lowercase()
                    val snippetLower = article.snippet.lowercase()
                    val isSpamOrBlog = blacklistKeywords.any { bl ->
                        titleLower.contains(bl) || snippetLower.contains(bl)
                    }
                    !isSpamOrBlog
                }.distinctBy { it.url }

                Log.d(TAG, "Interest '${interest.title}' got ${crawledArticles.size} raw articles, ${candidateArticles.size} passed Stage 1 Filter")

                // Stage 2 Filtering & Summarization
                for (article in candidateArticles) {
                    var summary = ""
                    var isVerified = true

                    if (isLlmAvailable && FridayApplication.llamaEngine.isModelLoaded() && llmInvocations < MAX_LLM_SUMMARY_PER_CRAWL) {
                        try {
                            Log.d(TAG, "LLM evaluating article: '${article.title}'")
                            val relevancePrompt = """
                            <|im_start|>system
                            You are an extremely strict AI news and updates curator. Your job is to filter out generic articles, blog posts, opinion pieces, tutorials, listicles, guidebooks, course promotions, and reviews.
                            You ONLY allow high-priority, factual, time-sensitive UPDATES and ANNOUNCEMENTS.
                            
                            Allowed updates:
                            1. Major Product/Model Releases (e.g., "Claude 3.5 Sonnet released", "OpenAI launches GPT-5").
                            2. Sports Match Results/Tournament Milestones (e.g., "New Zealand beat England in the second test", "India wins the match").
                            3. Hackathon/Event Registration/Hosting Announcements (e.g., "ISRO hosts a new national hackathon", "registrations open for Smart India Hackathon").
                            4. Major Tech Summit announcements (e.g., "Google I/O 2026 dates announced").
                            
                            Disallowed content:
                            1. Listicles, Guides, Tutorials, Opinions, and Tips (e.g., "How I made money...", "10 tips to build a machine learning model").
                            2. General non-update news or casual discussion/interviews.
                            3. Educational courses, advertisements, or generic educational articles.
                            
                            Answer with only YES if the item is a high-priority, factual update or announcement. Otherwise, answer with only NO.
                            <|im_end|>
                            <|im_start|>user
                            Topic Keywords: ${keywords.joinToString(", ")}
                            Item Title: ${article.title}
                            Item Snippet: ${article.snippet}
                            
                            Is this item a high-priority, factual, concrete update/announcement? Respond with only YES or NO.
                            <|im_end|>
                            <|im_start|>assistant
                            """.trimIndent()

                            val response = FridayApplication.llamaEngine.generate(relevancePrompt, maxTokens = 8, temp = 0.1f).trim()
                            isVerified = response.contains("YES", ignoreCase = true)
                            Log.d(TAG, "LLM relevance verification result: $isVerified ('$response')")

                            if (isVerified) {
                                val summarizePrompt = """
                                <|im_start|>system
                                You are a concise daily brief editor. Write a natural, exactly 2-sentence summary of the article. Do not include formatting, links, or bullet points.
                                <|im_end|>
                                <|im_start|>user
                                Article Title: ${article.title}
                                Article Snippet: ${article.snippet}
                                
                                Write the 2-sentence summary.
                                <|im_end|>
                                <|im_start|>assistant
                                """.trimIndent()

                                val rawSummary = FridayApplication.llamaEngine.generate(summarizePrompt, maxTokens = 120, temp = 0.5f).trim()
                                summary = rawSummary
                                    .replace("<|im_end|>", "")
                                    .replace("<|im_start|>", "")
                                    .trim()
                                llmInvocations++
                                Log.d(TAG, "LLM generated summary: '$summary'")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception running LLM filtering for: ${article.url}", e)
                            // Fallback to simple snippet
                            isVerified = true
                        }
                    }

                    if (!isLlmAvailable) {
                        // Apply stricter offline heuristic rules if LLM is not active to prevent spamming
                        val titleLower = article.title.lowercase()
                        val snippetLower = article.snippet.lowercase()
                        val updateKeywords = listOf(
                            "released", "launched", "announced", "beat", "wins", "won", "hosts", 
                            "register", "open", "release", "launch", "unveiled", "dates", "concludes", "schedule"
                        )
                        isVerified = updateKeywords.any { uk ->
                            titleLower.contains(uk) || snippetLower.contains(uk)
                        }
                    }

                    if (!isVerified) continue

                    // Fallback summary if LLM was skipped or capped
                    if (summary.isEmpty()) {
                        summary = if (article.snippet.length > 180) {
                            article.snippet.substring(0, 177) + "..."
                        } else {
                            article.snippet.ifEmpty { "No summary details available. Visit source link for full details." }
                        }
                    }

                    val entity = BriefItemEntity(
                        interestId = interest.id,
                        title = article.title,
                        summary = summary,
                        url = article.url,
                        sourceName = article.sourceName,
                        pubDate = article.pubDate,
                        relevanceScore = 1.0f,
                        status = "new"
                    )

                    val insertId = dao.insertBriefItem(entity)
                    if (insertId != -1L) {
                        Log.i(TAG, "[+] Successfully inserted brief item: '${article.title}' from ${article.sourceName}")
                    } else {
                        Log.d(TAG, "Ignoring duplicate feed article: '${article.url}'")
                    }
                }
                
                // Update interest crawl timestamp
                dao.updateInterest(interest.copy(lastCrawlTime = System.currentTimeMillis()))
            }

            Log.i(TAG, "Daily Brief background crawl successfully completed.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in Daily Brief crawler worker", e)
            Result.failure()
        }
    }
}
