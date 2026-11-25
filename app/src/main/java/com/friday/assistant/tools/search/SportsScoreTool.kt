package com.friday.assistant.tools.search

import android.content.Context
import android.util.Log
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Manages user's followed cricket and football teams in SharedPreferences.
 * Defaults to India (cricket) and Real Madrid (football).
 */
object SportsPrefs {
    private const val PREFS_NAME = "friday_sports_prefs"
    private const val KEY_CRICKET_TEAMS = "cricket_teams"
    private const val KEY_FOOTBALL_TEAMS = "football_teams"

    val DEFAULT_CRICKET_TEAMS = listOf("India")
    val DEFAULT_FOOTBALL_TEAMS = listOf("Real Madrid")

    private val FOOTBALL_KEYWORDS = setOf(
        "madrid", "barcelona", "barca", "arsenal", "liverpool", "chelsea", "city", "united",
        "bayern", "dortmund", "psg", "juventus", "milan", "inter", "atletico", "tottenham",
        "fc", "cf", "sc", "rovers", "wanderers", "athletic"
    )

    fun getCricketTeams(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CRICKET_TEAMS)) return DEFAULT_CRICKET_TEAMS
        val raw = prefs.getString(KEY_CRICKET_TEAMS, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getFootballTeams(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_FOOTBALL_TEAMS)) return DEFAULT_FOOTBALL_TEAMS
        val raw = prefs.getString(KEY_FOOTBALL_TEAMS, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getAllFollowedTeams(context: Context): List<String> {
        return (getCricketTeams(context) + getFootballTeams(context)).distinct()
    }

    fun addTeam(context: Context, teamName: String, isFootball: Boolean? = null): Pair<String, String> {
        val clean = teamName.trim()
        if (clean.isEmpty()) return Pair("", "")
        val football = isFootball ?: FOOTBALL_KEYWORDS.any { clean.lowercase(Locale.ROOT).contains(it) }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (football) {
            val current = getFootballTeams(context).toMutableList()
            if (current.none { it.equals(clean, ignoreCase = true) }) current.add(clean)
            prefs.edit().putString(KEY_FOOTBALL_TEAMS, current.joinToString(",")).apply()
            Pair(clean, "football")
        } else {
            val current = getCricketTeams(context).toMutableList()
            if (current.none { it.equals(clean, ignoreCase = true) }) current.add(clean)
            prefs.edit().putString(KEY_CRICKET_TEAMS, current.joinToString(",")).apply()
            Pair(clean, "cricket")
        }
    }

    fun removeTeam(context: Context, teamName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val c = getCricketTeams(context).filterNot { it.equals(teamName.trim(), ignoreCase = true) }
        val f = getFootballTeams(context).filterNot { it.equals(teamName.trim(), ignoreCase = true) }
        prefs.edit()
            .putString(KEY_CRICKET_TEAMS, c.joinToString(","))
            .putString(KEY_FOOTBALL_TEAMS, f.joinToString(","))
            .apply()
    }
}

data class CricketScoreItem(
    val rawTitle: String,
    val description: String
)

data class SportsScoreCard(
    val id: String,
    val sport: String,
    val title: String,
    val summary: String,
    val badge: String, // "LIVE", "FINAL", "NEXT", "FOLLOWING"
    val isLive: Boolean
)

data class FootballScoreItem(
    val eventId: String,
    val homeTeam: String,
    val homeAbbr: String,
    val awayTeam: String,
    val awayAbbr: String,
    val homeScore: String,
    val awayScore: String,
    val state: String, // "in", "post", "pre"
    val detail: String,
    val startIsoDate: String
)

class SportsScoreTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "SportsScoreTool"
        private const val CRICINFO_RSS_URL = "https://static.cricinfo.com/rss/livescores.xml"
        private val SOCCER_ENDPOINTS = listOf(
            "https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard",
            "https://site.api.espn.com/apis/site/v2/sports/soccer/uefa.champions/scoreboard",
            "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard",
            "https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard"
        )

        private val TEAM_ALIASES = mapOf(
            "india" to listOf("india", "ind", "india women", "india a"),
            "australia" to listOf("australia", "aus"),
            "england" to listOf("england", "eng"),
            "pakistan" to listOf("pakistan", "pak"),
            "south africa" to listOf("south africa", "rsa", "sa"),
            "new zealand" to listOf("new zealand", "nz"),
            "west indies" to listOf("west indies", "wi"),
            "sri lanka" to listOf("sri lanka", "sl"),
            "bangladesh" to listOf("bangladesh", "ban"),
            "afghanistan" to listOf("afghanistan", "afg"),
            "real madrid" to listOf("real madrid", "real madrid cf", "rma"),
            "barcelona" to listOf("barcelona", "fc barcelona", "barca"),
            "manchester city" to listOf("manchester city", "man city"),
            "manchester united" to listOf("manchester united", "man united", "man utd"),
            "mumbai indians" to listOf("mumbai indians", "mi"),
            "royal challengers bengaluru" to listOf("royal challengers", "rcb"),
            "chennai super kings" to listOf("chennai super kings", "csk")
        )

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(3500, TimeUnit.MILLISECONDS)
                .readTimeout(3500, TimeUnit.MILLISECONDS)
                .callTimeout(5000, TimeUnit.MILLISECONDS)
                .build()
        }

        internal fun parseCricinfoRss(xml: String): List<CricketScoreItem> {
            val items = mutableListOf<CricketScoreItem>()
            try {
                val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                for (el in doc.select("item")) {
                    val title = el.selectFirst("title")?.text()?.trim() ?: ""
                    val desc = el.selectFirst("description")?.text()?.trim() ?: ""
                    if (title.isNotBlank()) {
                        items.add(CricketScoreItem(title, desc))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Cricinfo RSS XML", e)
            }
            return items
        }

        internal fun formatCricketTitleForSpeech(rawTitle: String): String {
            var s = rawTitle
                .replace("*", "")
                .replace("&amp;", "and")
                .replace("&", "and")
                .replace(Regex("\\s+v\\s+", RegexOption.IGNORE_CASE), " vs ")
                .replace(Regex("(\\d+)/(\\d+)"), "$1 for $2")
                .replace(Regex("\\bov\\b", RegexOption.IGNORE_CASE), "overs")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (!s.endsWith(".")) s += "."
            return s
        }

        internal fun parseEspnSoccerScoreboard(json: String): List<FootballScoreItem> {
            val list = mutableListOf<FootballScoreItem>()
            try {
                val root = JsonParser.parseString(json).asJsonObject
                val events = root.getAsJsonArray("events") ?: return emptyList()
                for (el in events) {
                    val ev = el.asJsonObject
                    val eventId = ev.get("id")?.asString ?: continue
                    val startDate = ev.get("date")?.asString ?: ""
                    val comps = ev.getAsJsonArray("competitions") ?: continue
                    if (comps.size() == 0) continue
                    val comp = comps[0].asJsonObject

                    val statusObj = comp.getAsJsonObject("status")
                    val typeObj = statusObj?.getAsJsonObject("type")
                    val state = typeObj?.get("state")?.asString ?: "pre"
                    val detail = typeObj?.get("shortDetail")?.asString
                        ?: statusObj?.get("displayClock")?.asString
                        ?: ""

                    val competitors = comp.getAsJsonArray("competitors") ?: continue
                    var homeName = ""
                    var homeAbbr = ""
                    var homeScore = "0"
                    var awayName = ""
                    var awayAbbr = ""
                    var awayScore = "0"

                    for (cEl in competitors) {
                        val c = cEl.asJsonObject
                        val side = c.get("homeAway")?.asString ?: ""
                        val teamObj = c.getAsJsonObject("team")
                        val displayName = teamObj?.get("shortDisplayName")?.asString
                            ?: teamObj?.get("displayName")?.asString
                            ?: ""
                        val abbr = teamObj?.get("abbreviation")?.asString ?: ""
                        val score = c.get("score")?.asString ?: "0"

                        if (side == "home") {
                            homeName = displayName
                            homeAbbr = abbr
                            homeScore = score
                        } else {
                            awayName = displayName
                            awayAbbr = abbr
                            awayScore = score
                        }
                    }

                    if (homeName.isNotEmpty() && awayName.isNotEmpty()) {
                        list.add(
                            FootballScoreItem(
                                eventId = eventId,
                                homeTeam = homeName,
                                homeAbbr = homeAbbr,
                                awayTeam = awayName,
                                awayAbbr = awayAbbr,
                                homeScore = homeScore,
                                awayScore = awayScore,
                                state = state,
                                detail = detail,
                                startIsoDate = startDate
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse ESPN soccer JSON", e)
            }
            return list
        }

        internal fun formatFootballMatchForSpeech(match: FootballScoreItem): String {
            return when (match.state) {
                "in" -> {
                    val clock = match.detail.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                    "${match.homeTeam} ${match.homeScore}, ${match.awayTeam} ${match.awayScore}$clock."
                }
                "post" -> {
                    val h = match.homeScore.toIntOrNull()
                    val a = match.awayScore.toIntOrNull()
                    val statusTag = match.detail.ifBlank { "Full Time" }
                    if (h != null && a != null) {
                        when {
                            h > a -> "${match.homeTeam} beat ${match.awayTeam} $h to $a ($statusTag)."
                            a > h -> "${match.awayTeam} beat ${match.homeTeam} $a to $h ($statusTag)."
                            else -> "${match.homeTeam} drew $h to $a against ${match.awayTeam} ($statusTag)."
                        }
                    } else {
                        "${match.homeTeam} ${match.homeScore} vs ${match.awayTeam} ${match.awayScore} ($statusTag)."
                    }
                }
                else -> {
                    val localTime = formatIsoToLocal(match.startIsoDate) ?: match.detail
                    if (localTime.isNotBlank()) {
                        "${match.homeTeam} plays ${match.awayTeam} at $localTime."
                    } else {
                        "${match.homeTeam} vs ${match.awayTeam} is upcoming."
                    }
                }
            }
        }

        private fun formatIsoToLocal(iso: String): String? {
            if (iso.isBlank()) return null
            val patterns = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
            for (p in patterns) {
                try {
                    val parser = SimpleDateFormat(p, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val date = parser.parse(iso) ?: continue
                    val out = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
                    return out.format(date)
                } catch (_: Exception) {
                }
            }
            return null
        }

        internal fun matchesTeam(haystack: String, teamName: String): Boolean {
            val lower = haystack.lowercase(Locale.ROOT)
            val key = teamName.trim().lowercase(Locale.ROOT)
            if (key.isEmpty()) return false
            val variants = TEAM_ALIASES[key] ?: listOf(key)
            return variants.any { variant ->
                Regex("\\b${Regex.escape(variant)}\\b").containsMatchIn(lower)
            }
        }
    }

    override val name: String = "sports_score"

    override val description: String = """
        Fetches concise live, recent, or upcoming cricket and football scores, prioritizing user-followed teams (India, Real Madrid).
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Original sports score query"
            },
            "sport": {
              "type": "string",
              "enum": ["cricket", "football", "auto"],
              "description": "Target sport or auto"
            },
            "team": {
              "type": "string",
              "description": "Optional team name"
            }
          }
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val rawQuery = args.get("query")?.asString?.trim() ?: ""
        val sportArg = args.get("sport")?.asString?.lowercase(Locale.ROOT) ?: "auto"
        val teamArg = args.get("team")?.asString?.trim() ?: ""

        val lower = rawQuery.lowercase(Locale.ROOT)
        val followedCricket = SportsPrefs.getCricketTeams(context)
        val followedFootball = SportsPrefs.getFootballTeams(context)

        val explicitTeam = teamArg.ifEmpty {
            detectMentionedTeam(lower, followedCricket + followedFootball)
        }

        val targetSport = when {
            sportArg == "cricket" || sportArg == "football" -> sportArg
            explicitTeam.isNotEmpty() && followedCricket.any { it.equals(explicitTeam, ignoreCase = true) } -> "cricket"
            explicitTeam.isNotEmpty() && followedFootball.any { it.equals(explicitTeam, ignoreCase = true) } -> "football"
            lower.contains("cricket") || lower.contains("ipl") || lower.contains("bcci") ||
                lower.contains("t20") || lower.contains("odi") || lower.contains("test match") ||
                lower.contains("world cup") && lower.contains("cricket") -> "cricket"
            lower.contains("football") || lower.contains("soccer") || lower.contains("la liga") ||
                lower.contains("laliga") || lower.contains("champions league") || lower.contains("ucl") ||
                lower.contains("premier league") || lower.contains("epl") || lower.contains("real madrid") ||
                lower.contains("madrid") || lower.contains("barcelona") || lower.contains("arsenal") ||
                lower.contains("liverpool") || lower.contains("chelsea") || lower.contains("man city") ||
                lower.contains("man united") -> "football"
            else -> "auto"
        }

        when (targetSport) {
            "cricket" -> {
                val reply = queryCricket(explicitTeam, followedCricket)
                ToolResult(true, reply)
            }
            "football" -> {
                val reply = queryFootball(explicitTeam, followedFootball)
                ToolResult(true, reply)
            }
            else -> {
                val (cricketReply, footballReply) = coroutineScope {
                    val cDef = async { queryCricket(explicitTeam, followedCricket, followedOnly = true) }
                    val fDef = async { queryFootball(explicitTeam, followedFootball, followedOnly = true) }
                    Pair(cDef.await(), fDef.await())
                }
                val combined = listOfNotNull(cricketReply, footballReply).joinToString(" ")
                if (combined.isNotBlank()) {
                    ToolResult(true, combined)
                } else {
                    // Fallback to general live cricket or football if neither followed team has an active game
                    val generalCricket = queryCricket("", followedCricket)
                    ToolResult(true, generalCricket)
                }
            }
        }
    }

    private fun detectMentionedTeam(lowerQuery: String, followedTeams: List<String>): String {
        for (team in followedTeams) {
            if (matchesTeam(lowerQuery, team)) return team
        }
        for (key in TEAM_ALIASES.keys) {
            if (matchesTeam(lowerQuery, key)) return key
        }
        return ""
    }

    private fun fetchUrl(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; SM-S921B) AppleWebKit/537.36")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP fetch failed for $url: ${e.message}")
            null
        }
    }

    private fun queryCricket(explicitTeam: String, followedTeams: List<String>): String {
        return queryCricket(explicitTeam, followedTeams, followedOnly = false)
            ?: "I couldn't fetch live cricket scores right now."
    }

    private fun queryCricket(explicitTeam: String, followedTeams: List<String>, followedOnly: Boolean): String? {
        val xml = fetchUrl(CRICINFO_RSS_URL) ?: return if (followedOnly) null else "I couldn't reach the cricket score feed right now."
        val matches = parseCricinfoRss(xml)
        if (matches.isEmpty()) {
            return if (followedOnly) null else "There are no live cricket matches listed right now."
        }

        if (explicitTeam.isNotEmpty()) {
            val matched = matches.filter { matchesTeam("${it.rawTitle} ${it.description}", explicitTeam) }
            if (matched.isNotEmpty()) {
                return matched.take(2).joinToString(" ") { formatCricketTitleForSpeech(it.rawTitle) }
            }
            if (followedOnly) return null
            val top = matches.take(2).joinToString(" ") { formatCricketTitleForSpeech(it.rawTitle) }
            return "No live $explicitTeam match right now. Currently: $top"
        }

        val followedMatches = matches.filter { m ->
            followedTeams.any { team -> matchesTeam("${m.rawTitle} ${m.description}", team) }
        }
        if (followedMatches.isNotEmpty()) {
            return followedMatches.take(2).joinToString(" ") { formatCricketTitleForSpeech(it.rawTitle) }
        }
        if (followedOnly) return null

        return matches.take(2).joinToString(" ") { formatCricketTitleForSpeech(it.rawTitle) }
    }

    private suspend fun queryFootball(explicitTeam: String, followedTeams: List<String>): String {
        return queryFootball(explicitTeam, followedTeams, followedOnly = false)
            ?: "I couldn't find any live football scores right now."
    }

    private suspend fun queryFootball(explicitTeam: String, followedTeams: List<String>, followedOnly: Boolean): String? {
        // Query a 3-day window (yesterday to tomorrow) so recent FT results and upcoming fixtures are visible
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val startStr = fmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 2)
        val endStr = fmt.format(cal.time)
        val dateRange = "$startStr-$endStr"

        val allMatches = coroutineScope {
            SOCCER_ENDPOINTS.map { base ->
                async {
                    val url = if (base.endsWith("/all/scoreboard")) base else "$base?dates=$dateRange"
                    val body = fetchUrl(url) ?: return@async emptyList<FootballScoreItem>()
                    parseEspnSoccerScoreboard(body)
                }
            }.awaitAll().flatten().distinctBy { it.eventId }
        }

        if (allMatches.isEmpty()) {
            return if (followedOnly) null else "I couldn't fetch football scores right now."
        }

        val statePriority = mapOf("in" to 0, "post" to 1, "pre" to 2)

        if (explicitTeam.isNotEmpty()) {
            val matched = allMatches
                .filter { matchesTeam("${it.homeTeam} ${it.awayTeam} ${it.homeAbbr} ${it.awayAbbr}", explicitTeam) }
                .sortedBy { statePriority[it.state] ?: 3 }
            if (matched.isNotEmpty()) {
                return formatFootballMatchForSpeech(matched.first())
            }
            if (followedOnly) return null
            return "No recent or upcoming match found for $explicitTeam in the current matchday."
        }

        val followedMatches = allMatches
            .filter { m ->
                followedTeams.any { t -> matchesTeam("${m.homeTeam} ${m.awayTeam} ${m.homeAbbr} ${m.awayAbbr}", t) }
            }
            .sortedBy { statePriority[it.state] ?: 3 }

        if (followedMatches.isNotEmpty()) {
            return formatFootballMatchForSpeech(followedMatches.first())
        }
        if (followedOnly) return null

        val liveOrRecent = allMatches
            .filter { it.state == "in" || it.state == "post" }
            .sortedBy { statePriority[it.state] ?: 3 }
            .take(2)
        return if (liveOrRecent.isNotEmpty()) {
            liveOrRecent.joinToString(" ") { formatFootballMatchForSpeech(it) }
        } else {
            formatFootballMatchForSpeech(allMatches.first())
        }
    }

    suspend fun fetchDashboardCards(forceRefresh: Boolean = false): List<SportsScoreCard> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedCards.isNotEmpty() && now - lastCardsFetchMs < 120_000L) {
            return cachedCards
        }
        val cricketTeams = SportsPrefs.getCricketTeams(context)
        val footballTeams = SportsPrefs.getFootballTeams(context)
        val cards = mutableListOf<SportsScoreCard>()

        coroutineScope {
            val cricketJob = async {
                val xml = fetchUrl(CRICINFO_RSS_URL) ?: return@async emptyList<SportsScoreCard>()
                val matches = parseCricinfoRss(xml)
                if (matches.isEmpty()) return@async emptyList<SportsScoreCard>()
                val followed = matches.filter { m ->
                    cricketTeams.any { team -> matchesTeam("${m.rawTitle} ${m.description}", team) }
                }
                val picks = if (followed.isNotEmpty()) followed.take(2) else matches.take(1)
                picks.mapIndexed { idx, m ->
                    val speech = formatCricketTitleForSpeech(m.rawTitle)
                    SportsScoreCard(
                        id = "cric_$idx",
                        sport = "Cricket",
                        title = m.rawTitle.substringBefore("*").trim().take(48),
                        summary = speech,
                        badge = if (followed.isNotEmpty()) "LIVE" else "MATCH",
                        isLive = m.rawTitle.contains("*") || followed.isNotEmpty()
                    )
                }
            }

            val footballJob = async {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                cal.add(Calendar.DAY_OF_YEAR, -1)
                val startStr = fmt.format(cal.time)
                cal.add(Calendar.DAY_OF_YEAR, 2)
                val dateRange = "$startStr-${fmt.format(cal.time)}"
                val allMatches = SOCCER_ENDPOINTS.map { base ->
                    async {
                        val url = if (base.endsWith("/all/scoreboard")) base else "$base?dates=$dateRange"
                        val body = fetchUrl(url) ?: return@async emptyList<FootballScoreItem>()
                        parseEspnSoccerScoreboard(body)
                    }
                }.awaitAll().flatten().distinctBy { it.eventId }

                val statePriority = mapOf("in" to 0, "post" to 1, "pre" to 2)
                val followed = allMatches.filter { m ->
                    footballTeams.any { t -> matchesTeam("${m.homeTeam} ${m.awayTeam} ${m.homeAbbr} ${m.awayAbbr}", t) }
                }.sortedBy { statePriority[it.state] ?: 3 }

                val picks = if (followed.isNotEmpty()) followed.take(2) else allMatches.sortedBy { statePriority[it.state] ?: 3 }.take(1)
                picks.map { m ->
                    val badge = when (m.state) {
                        "in" -> "LIVE"
                        "post" -> "FINAL"
                        else -> "NEXT"
                    }
                    SportsScoreCard(
                        id = "fb_${m.eventId}",
                        sport = "Football",
                        title = "${m.homeAbbr.ifBlank { m.homeTeam }} vs ${m.awayAbbr.ifBlank { m.awayTeam }}",
                        summary = formatFootballMatchForSpeech(m),
                        badge = badge,
                        isLive = m.state == "in"
                    )
                }
            }

            cards.addAll(cricketJob.await())
            cards.addAll(footballJob.await())
        }

        if (cards.isNotEmpty()) {
            cachedCards = cards
            lastCardsFetchMs = now
        }
        return cards
    }
}

private var cachedCards: List<SportsScoreCard> = emptyList()
private var lastCardsFetchMs: Long = 0L
