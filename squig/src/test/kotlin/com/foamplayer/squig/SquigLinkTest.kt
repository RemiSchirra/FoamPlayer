package com.foamplayer.squig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SquigLinkTest {

    // -- squigsites.json -------------------------------------------------------

    private val sitesJson = """
        [
          {
            "username": "kazi",
            "name": "Kazi",
            "urlType": "subdomain",
            "dbs": [ { "type": "IEMs", "folder": "/" } ]
          },
          {
            "username": "elrics",
            "name": "Elrics",
            "urlType": "labFolder",
            "dbs": [
              { "type": "IEMs", "folder": "/" },
              { "type": "Headphones", "folder": "/headphones/" }
            ]
          },
          {
            "username": "crinacle",
            "name": "Hangout.Audio",
            "urlType": "altDomain",
            "altDomain": "https://graph.hangout.audio",
            "dbs": [ { "type": "5128", "folder": "/iem/5128/" } ]
          },
          { "username": "empty", "name": "Empty", "urlType": "subdomain", "dbs": [] }
        ]
    """.trimIndent()

    @Test
    fun `sites parse from the instance index`() {
        val sites = parseSites(sitesJson)
        assertEquals(3, sites.size)

        val kazi = sites.first { it.username == "kazi" }
        assertEquals("https://kazi.squig.link", kazi.baseUrl)
        assertEquals(listOf("IEMs"), kazi.dbs.map { it.type })

        val elrics = sites.first { it.username == "elrics" }
        assertEquals("https://squig.link/lab/elrics", elrics.baseUrl)
        assertEquals("Headphones", elrics.dbs[1].type)
        assertEquals("/headphones/", elrics.dbs[1].folder)

        val hangout = sites.first { it.username == "crinacle" }
        assertEquals("https://graph.hangout.audio", hangout.baseUrl)
    }

    // -- phone_book.json -------------------------------------------------------

    private val phoneBookJson = """
        [
          {
            "name": "64 Audio",
            "phones": [
              { "name": "U12t", "file": "64 Audio U12t", "reviewScore": "4" },
              { "name": "Duo", "file": ["64 Audio Duo (unit 1)", "64 Audio Duo (unit 2)"] }
            ]
          },
          {
            "name": "_EQ",
            "phones": [ { "name": "Sandbox", "file": "EQ sandbox" } ]
          },
          {
            "name": "Moondrop",
            "phones": [ { "name": "Aria", "reviewScore": "3.5" } ]
          }
        ]
    """.trimIndent()

    @Test
    fun `phone book parses brands models and file fields`() {
        val devices = parsePhoneBook(phoneBookJson)
        // All brands parse (tool brands included); only file-less phones drop.
        assertEquals(3, devices.size)

        val u12t = devices.first { it.model == "U12t" }
        assertEquals("64 Audio", u12t.brand)
        assertEquals("64 Audio U12t", u12t.file)
        assertEquals("4", u12t.reviewScore)
        assertFalse(u12t.isTarget)

        // Array `file` fields fall back to the first unit measurement.
        val duo = devices.first { it.model == "Duo" }
        assertEquals("64 Audio Duo (unit 1)", duo.file)

        // The sandbox tool brand parses like any other brand.
        assertTrue(devices.any { it.model == "Sandbox" })
        // The phone without a measurement file is dropped.
        assertTrue(devices.none { it.model == "Aria" })
    }

    // -- config.js ---------------------------------------------------------------

    private val configJs = """
        const init_phones = ["Sennheiser IE 200"],
              DIR = "data/",
              data_format = "REW";
        const targets = [
            { type:"Neutral",    files:["Diffuse Field","Etymotic"] },
            // { type:"Dead", files:["Ignored Entry"] },
            { type:"Preference",
              files:[
                  "Harman",
                  "Rtings"
              ] }
        ];
        const num_samples = 3;
    """.trimIndent()

    @Test
    fun `config targets parse with the Target file suffix`() {
        val targets = parseConfigTargets(stripLineComments(configJs))
        assertEquals(4, targets.size)

        val diffuse = targets.first()
        assertEquals("Neutral", diffuse.brand)
        assertEquals("Diffuse Field", diffuse.model)
        assertEquals("Diffuse Field Target", diffuse.file)
        assertTrue(diffuse.isTarget)

        // Commented-out entries don't leak in.
        assertTrue(targets.none { it.model == "Ignored Entry" })

        val harman = targets.first { it.model == "Harman" }
        assertEquals("Preference", harman.brand)
        assertEquals("Harman Target", harman.file)
    }

    @Test
    fun `data dir and sample count parse from config`() {
        assertEquals("data/", parseDataDir(configJs))
        assertEquals(3, parseNumSamples(configJs))
        assertEquals("data/", parseDataDir("const foo = 1;"))
        assertEquals(1, parseNumSamples("const foo = 1;"))
    }

    // -- measurement files -------------------------------------------------------

    private val rewFile = """
        * Measurement data measured by REW V5.20.3
        * Source: Microphone, L
        * Freq(Hz)    Level(dB)
        *
          20.3,        -5.2
          21.5,        -4.1
          1000.0,      0.0
          20000,       3.4
    """.trimIndent()

    @Test
    fun `rew text parses to a sorted curve ignoring comments`() {
        val curve = parseFrequencyResponse(rewFile)
        assertFalse(curve.isEmpty)
        assertEquals(20.3f, curve.minFrequencyHz, 0.001f)
        assertEquals(20000f, curve.maxFrequencyHz, 0.001f)
        assertEquals(0f, curve.interpolate(1000f), 0.001f)
        assertEquals(-5.2f, curve.interpolate(20f), 0.001f)
    }

    @Test
    fun `empty or garbage files yield an empty curve`() {
        assertTrue(parseFrequencyResponse("").isEmpty)
        assertTrue(parseFrequencyResponse("* just comments\n*\n").isEmpty)
    }
}
