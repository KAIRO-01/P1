package app.shilex.android.live

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveProtocolTest {
    private val setup = LiveProtocol.Setup(
        model = "gemini-3.1-flash-live-preview",
        systemInstruction = "Be brief.",
        voice = "Puck",
        languages = listOf("en-IN", "hi-IN"),
        resumeHandle = null,
    )

    @Test
    fun setupMatchesDesktopSession() {
        val body = JSONObject(LiveProtocol.setupMessage(setup)).getJSONObject("setup")
        assertEquals("models/gemini-3.1-flash-live-preview", body.getString("model"))
        val generation = body.getJSONObject("generationConfig")
        assertEquals("AUDIO", generation.getJSONArray("responseModalities").getString(0))
        assertEquals(
            "Puck",
            generation.getJSONObject("speechConfig").getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig").getString("voiceName"),
        )
        assertEquals("Be brief.", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(2, body.getJSONObject("inputAudioTranscription").getJSONArray("languageCodes").length())
        assertTrue(body.has("outputAudioTranscription"))
        assertFalse(body.getJSONObject("sessionResumption").has("handle"))
        val detection = body.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertEquals(700, detection.getInt("silenceDurationMs"))
    }

    @Test
    fun setupCarriesResumeHandle() {
        val body = JSONObject(LiveProtocol.setupMessage(setup.copy(resumeHandle = "abc"))).getJSONObject("setup")
        assertEquals("abc", body.getJSONObject("sessionResumption").getString("handle"))
    }

    @Test
    fun googleSearchMustBeExplicitlyEnabled() {
        val off = JSONObject(LiveProtocol.setupMessage(setup)).getJSONObject("setup")
        assertFalse(off.has("tools"))
        val on = JSONObject(LiveProtocol.setupMessage(setup.copy(googleSearch = true)))
            .getJSONObject("setup").getJSONArray("tools")
        assertEquals(1, on.length())
        assertTrue(on.getJSONObject(0).has("googleSearch"))
    }

    @Test
    fun searchAndPhoneToolCanRunTogether() {
        val tool = JSONObject().put("name", "get_weather")
        val config = setup.copy(tools = org.json.JSONArray().put(tool), googleSearch = true)
        val enabled = JSONObject(LiveProtocol.setupMessage(config))
            .getJSONObject("setup").getJSONArray("tools")
        assertEquals(2, enabled.length())
        assertTrue(enabled.getJSONObject(0).has("functionDeclarations"))
        assertTrue(enabled.getJSONObject(1).has("googleSearch"))
    }

    @Test
    fun parsesGroundingSourcesAndSearchSuggestions() {
        val metadata = JSONObject()
            .put("groundingChunks", org.json.JSONArray().put(JSONObject()
                .put("web", JSONObject().put("uri", "https://example.com/teak")
                    .put("title", "Teak listing"))))
            .put("webSearchQueries", org.json.JSONArray().put("teak price"))
            .put("searchEntryPoint", JSONObject().put("renderedContent", "<div>Search</div>"))
        val events = LiveProtocol.parse(
            JSONObject().put("serverContent", JSONObject().put("groundingMetadata", metadata)).toString()
        )
        assertEquals(1, events.size)
        val info = events.first() as LiveEvent.Grounding
        assertEquals("https://example.com/teak", info.sources.first().url)
        assertEquals("teak price", info.queries.first())
        assertTrue(info.searchSuggestionsHtml.contains("Search"))
    }

    @Test
    fun audioIsBase64Pcm16k() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val audio = JSONObject(LiveProtocol.audioMessage(pcm)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertArrayEquals(pcm, Base64.getDecoder().decode(audio.getString("data")))
    }

    @Test
    fun parsesServerContentInOrder() {
        val data = Base64.getEncoder().encodeToString(byteArrayOf(9, 9))
        val message = """
            {"serverContent": {
              "inputTranscription": {"text": "namaste"},
              "modelTurn": {"parts": [{"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "$data"}}]},
              "outputTranscription": {"text": "Hello!"},
              "turnComplete": true
            }}
        """.trimIndent()
        val events = LiveProtocol.parse(message)
        assertEquals(LiveEvent.UserText("namaste"), events[0])
        val audio = events[1] as LiveEvent.Audio
        assertEquals(24000, audio.sampleRate)
        assertArrayEquals(byteArrayOf(9, 9), audio.pcm)
        assertEquals(LiveEvent.AssistantText("Hello!"), events[2])
        assertEquals(LiveEvent.TurnComplete, events[3])
    }

    @Test
    fun parsesControlMessages() {
        assertEquals(listOf(LiveEvent.SetupComplete), LiveProtocol.parse("""{"setupComplete": {}}"""))
        assertEquals(listOf(LiveEvent.GoAway), LiveProtocol.parse("""{"goAway": {"timeLeft": "10s"}}"""))
        assertEquals(
            listOf(LiveEvent.ResumeHandle("h1")),
            LiveProtocol.parse("""{"sessionResumptionUpdate": {"newHandle": "h1", "resumable": true}}"""),
        )
        assertTrue(LiveProtocol.parse("""{"sessionResumptionUpdate": {"resumable": false}}""").isEmpty())
        assertEquals(
            listOf(LiveEvent.Interrupted),
            LiveProtocol.parse("""{"serverContent": {"interrupted": true}}"""),
        )
    }

    @Test
    fun ignoresGarbage() {
        assertTrue(LiveProtocol.parse("not json").isEmpty())
    }

    @Test
    fun closeReasonsAreFriendly() {
        assertTrue("key" in LiveProtocol.describeClose(1007, "API key not valid. Please pass a valid API key."))
        assertTrue("rate limit" in LiveProtocol.describeClose(1011, "You exceeded your current quota"))
        assertEquals("Lost the connection to Gemini.", LiveProtocol.describeClose(-1, ""))
    }
}
