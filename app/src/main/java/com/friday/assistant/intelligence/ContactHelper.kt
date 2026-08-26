package com.friday.assistant.intelligence

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import java.util.Locale

data class ContactEntry(
    val name: String,
    val normalizedName: String,
    val phoneNumber: String? = null
)

object ContactHelper {
    private const val TAG = "ContactHelper"
    private var cachedContacts: List<ContactEntry>? = null
    private var lastCacheTimeMs: Long = 0L
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    // Known common phonetic/ASR mishearings in Indian context
    private val KNOWN_ASR_NAME_MAP = mapOf(
        "connecting" to "kanak",
        "connect" to "kanak",
        "conic" to "kanak",
        "raw hit" to "rohit",
        "row hit" to "rohit",
        "prayer" to "priya",
        "poo ja" to "pooja",
        "shub ham" to "shubham",
        "are ya" to "arya",
        "deep pack" to "deepak",
        "so raj" to "suraj",
        "are on" to "aaron",
        "are man" to "armaan"
    )

    fun getContacts(context: Context): List<ContactEntry> {
        val now = System.currentTimeMillis()
        cachedContacts?.let {
            if (now - lastCacheTimeMs < CACHE_TTL_MS) {
                return it
            }
        }

        val contacts = mutableListOf<ContactEntry>()
        try {
            val contentResolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = if (nameIdx != -1) it.getString(nameIdx) else null
                    val num = if (numIdx != -1) it.getString(numIdx) else null
                    if (!name.isNullOrBlank()) {
                        contacts.add(
                            ContactEntry(
                                name = name.trim(),
                                normalizedName = name.trim().lowercase(Locale.getDefault()),
                                phoneNumber = num?.trim()
                            )
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Contacts permission not granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching contacts", e)
        }

        cachedContacts = contacts
        lastCacheTimeMs = now
        Log.d(TAG, "Loaded ${contacts.size} contacts into cache")
        return contacts
    }

    /**
     * Attempts to find the best matching contact name on the device given an ASR candidate name.
     */
    fun findBestMatchingContact(context: Context, candidate: String): String? {
        val trimmed = candidate.trim().lowercase(Locale.getDefault())
        if (trimmed.isEmpty()) return null

        val contacts = getContacts(context)
        if (contacts.isEmpty()) return null

        // 1. Exact match
        contacts.firstOrNull { it.normalizedName == trimmed }?.let { return it.name }

        // 2. Direct check in known ASR map (e.g. "connecting" -> "kanak")
        val mappedName = KNOWN_ASR_NAME_MAP[trimmed]
        if (mappedName != null) {
            contacts.firstOrNull { it.normalizedName.contains(mappedName) }?.let { return it.name }
        }

        // 3. First name match or starts-with match
        contacts.firstOrNull { it.normalizedName.startsWith(trimmed) }?.let { return it.name }
        contacts.firstOrNull { it.normalizedName.split(" ").firstOrNull() == trimmed }?.let { return it.name }

        // 4. Word-level contains
        contacts.firstOrNull { it.normalizedName.contains(trimmed) }?.let { return it.name }

        // 5. Fuzzy Levenshtein match across first names and full names
        var bestMatch: String? = null
        var minDistance = Int.MAX_VALUE

        val maxAllowedDistance = when {
            trimmed.length <= 4 -> 1
            trimmed.length <= 7 -> 2
            else -> 3
        }

        for (contact in contacts) {
            val contactFirst = contact.normalizedName.split(" ").firstOrNull() ?: contact.normalizedName
            val dist = levenshteinDistance(trimmed, contactFirst)
            if (dist <= maxAllowedDistance && dist < minDistance) {
                minDistance = dist
                bestMatch = contact.name
            }
        }

        // 6. Consonant skeleton match (e.g. "connecting" -> "cnctng", "kanak" -> "knk")
        if (bestMatch == null) {
            val candidateConsonants = getConsonants(trimmed)
            for (contact in contacts) {
                val contactConsonants = getConsonants(contact.normalizedName.split(" ").firstOrNull() ?: "")
                if (contactConsonants.isNotEmpty() &&
                    (contactConsonants == candidateConsonants ||
                     candidateConsonants.startsWith(contactConsonants) ||
                     contactConsonants.startsWith(candidateConsonants))) {
                    return contact.name
                }
            }
        }

        return bestMatch
    }

    /**
     * Scans a full voice transcript and corrects common mis-heard terms and contact names.
     */
    fun correctTranscript(context: Context, rawText: String): String {
        var text = rawText.trim()

        // 1. General phonetic substitutions for common voice commands
        val generalReplacements = listOf(
            Regex("(?i)\\bsoch\\b") to "search",
            Regex("(?i)\\bsurch\\b") to "search",
            Regex("(?i)\\bsharch\\b") to "search",
            Regex("(?i)\\bbalanc\\b") to "balance",
            Regex("(?i)\\bbanalcne\\b") to "balance",
            Regex("(?i)\\bwhatapp\\b") to "whatsapp",
            Regex("(?i)\\bwatsapp\\b") to "whatsapp",
            Regex("(?i)\\bwhat's app\\b") to "whatsapp"
        )

        for ((regex, replacement) in generalReplacements) {
            text = text.replace(regex, replacement)
        }

        // 2. Detect contact targets in messaging/calling phrases
        // e.g. "send message to [name] saying [message]"
        // e.g. "call [name]"
        val contactPatterns = listOf(
            Regex("(?i)(?:send a message to|send message to|message|text|whatsapp)\\s+([a-zA-Z0-9]+)\\s+(?:saying|that|with|message)\\b"),
            Regex("(?i)(?:send a message to|send message to|message|text|whatsapp)\\s+([a-zA-Z0-9]+)$"),
            Regex("(?i)(?:call|dial|ring|phone)\\s+([a-zA-Z0-9]+)\\b")
        )

        for (pat in contactPatterns) {
            val match = pat.find(text)
            if (match != null) {
                val candidateName = match.groupValues[1]
                val matchedContact = findBestMatchingContact(context, candidateName)
                if (matchedContact != null && !matchedContact.equals(candidateName, ignoreCase = true)) {
                    Log.i(TAG, "Phonetic contact correction: '$candidateName' -> '$matchedContact'")
                    val range = match.groups[1]!!.range
                    text = text.replaceRange(range, matchedContact)
                    break
                }
            }
        }

        return text
    }

    private fun getConsonants(s: String): String {
        // Treat c as k for Indian phonetic alignment
        return s.lowercase(Locale.getDefault())
            .replace('c', 'k')
            .filter { it !in "aeiou " }
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
