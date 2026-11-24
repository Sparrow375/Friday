package com.friday.assistant.tools.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsScoreToolTest {

    @Test
    fun parseCricinfoRss_andFormatForSpeech() {
        val sampleXml = """
            <rss version="2.0">
              <channel>
                <title>Cricinfo Live Scores</title>
                <item>
                  <title>India 245/3 * v Australia 210</title>
                  <description>India 245/3 (38.2 ov)</description>
                </item>
                <item>
                  <title>England 180/6 (20 ov) v New Zealand 175/8</title>
                  <description>England won by 5 runs</description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val items = SportsScoreTool.parseCricinfoRss(sampleXml)
        assertEquals(2, items.size)
        assertEquals("India 245/3 * v Australia 210", items[0].rawTitle)

        val spoken = SportsScoreTool.formatCricketTitleForSpeech(items[0].rawTitle)
        assertEquals("India 245 for 3 vs Australia 210.", spoken)

        val spokenOvers = SportsScoreTool.formatCricketTitleForSpeech(items[1].rawTitle)
        assertEquals("England 180 for 6 (20 overs) vs New Zealand 175 for 8.", spokenOvers)
    }

    @Test
    fun parseEspnSoccerScoreboard_formatsLiveAndCompletedMatches() {
        val sampleJson = """
            {
              "events": [
                {
                  "id": "101",
                  "date": "2026-11-25T20:00Z",
                  "competitions": [
                    {
                      "status": {
                        "displayClock": "67'",
                        "type": { "state": "in", "shortDetail": "67'" }
                      },
                      "competitors": [
                        {
                          "homeAway": "home",
                          "score": "2",
                          "team": { "shortDisplayName": "Real Madrid", "abbreviation": "RMA" }
                        },
                        {
                          "homeAway": "away",
                          "score": "1",
                          "team": { "shortDisplayName": "Barcelona", "abbreviation": "BAR" }
                        }
                      ]
                    }
                  ]
                },
                {
                  "id": "102",
                  "date": "2026-11-24T20:00Z",
                  "competitions": [
                    {
                      "status": {
                        "type": { "state": "post", "shortDetail": "FT" }
                      },
                      "competitors": [
                        {
                          "homeAway": "home",
                          "score": "1",
                          "team": { "shortDisplayName": "Arsenal", "abbreviation": "ARS" }
                        },
                        {
                          "homeAway": "away",
                          "score": "3",
                          "team": { "shortDisplayName": "Liverpool", "abbreviation": "LIV" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val matches = SportsScoreTool.parseEspnSoccerScoreboard(sampleJson)
        assertEquals(2, matches.size)

        val liveSpeech = SportsScoreTool.formatFootballMatchForSpeech(matches[0])
        assertEquals("Real Madrid 2, Barcelona 1 (67').", liveSpeech)

        val ftSpeech = SportsScoreTool.formatFootballMatchForSpeech(matches[1])
        assertEquals("Liverpool beat Arsenal 3 to 1 (FT).", ftSpeech)
    }

    @Test
    fun matchesTeam_respectsWordBoundariesAndAliases() {
        assertTrue(SportsScoreTool.matchesTeam("IND 180/2 vs AUS", "India"))
        assertTrue(SportsScoreTool.matchesTeam("Real Madrid vs Getafe", "Real Madrid"))
        assertFalse(SportsScoreTool.matchesTeam("Indiana Pacers vs Heat", "India"))
    }
}
