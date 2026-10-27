package com.softalarm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoicesTest {

    // The 12 voices, exactly as named in your file (English = no suffix, then _pa, _ur, _hi)
    private val bases = listOf(
        "mother", "father", "sister", "brother", "grandmother", "husband",
        "wife", "motivational_coach", "boss", "employee", "azan", "child_baby"
    )
    private val suffixes = listOf("", "_pa", "_ur", "_hi")
    private val systemKeys = listOf("default", "default_file", "custom")

    private val voiceKeys = Voices.all.map { it.key }.filter { it !in systemKeys }

    @Test
    fun threeSystemChoicesPlusFortyEightVoices() {
        assertEquals(51, Voices.all.size)
        assertEquals(48, voiceKeys.size)
        assertEquals(systemKeys, Voices.all.take(3).map { it.key })
    }

    @Test
    fun theVoicesAreExactlyYourTwelveInYourOrderWithFourLanguagesEach() {
        val expected = bases.flatMap { b -> suffixes.map { s -> b + s } }
        assertEquals(expected, voiceKeys)
    }

    @Test
    fun keysAreUniqueAndAreValidMp3FileNames() {
        assertEquals(Voices.all.size, Voices.all.map { it.key }.toSet().size)
        val ok = Regex("^[a-z]+(_[a-z]+)*$")
        for (v in Voices.all) assertTrue("bad key: " + v.key, ok.matches(v.key))
        for (v in Voices.all) assertEquals("audio/" + v.key + ".mp3", v.assetPath)
    }

    @Test
    fun labelsAreUniqueAndNameTheLanguage() {
        assertEquals(Voices.all.size, Voices.all.map { it.label }.toSet().size)
        assertEquals("Mother · English", Voices.byKey("mother")!!.label)
        assertEquals("Mother · Punjabi", Voices.byKey("mother_pa")!!.label)
        assertEquals("Mother · Urdu", Voices.byKey("mother_ur")!!.label)
        assertEquals("Mother · Hindi", Voices.byKey("mother_hi")!!.label)
        assertEquals("Motivational Coach · Hindi", Voices.byKey("motivational_coach_hi")!!.label)
        assertEquals("Child / Little Baby · Urdu", Voices.byKey("child_baby_ur")!!.label)
    }

    @Test
    fun theThreeSystemChoicesAreAlwaysFree() {
        for (k in systemKeys) assertFalse(k, Voices.isPro(k))
    }

    @Test
    fun proHelpersAgreeWithTheList() {
        assertEquals(Voices.all.any { it.pro }, Voices.hasProVoices())
        for (v in Voices.all) assertEquals(v.pro, Voices.isPro(v.key))
        assertFalse(Voices.isPro("not-a-voice"))
    }

    @Test
    fun anOldSavedVoiceThatNoLongerExistsFallsBackToThePhoneTone() {
        assertEquals("mother_ur", Voices.normalize("mother_ur"))
        assertEquals("custom", Voices.normalize("custom"))
        assertEquals("default", Voices.normalize("grandfather"))      // from an older version of the app
        assertEquals("default", Voices.normalize("quran_ur"))
        assertEquals("default", Voices.normalize(""))
        assertEquals("Phone alarm tone (default)", Voices.labelFor("grandfather"))
    }

    @Test
    fun genderTagsOnlyWhereTheNameItselfSaysIt() {
        for (k in listOf("mother", "sister", "grandmother", "wife")) assertEquals(k, "female", Voices.byKey(k)!!.gender)
        for (k in listOf("father", "brother", "husband")) assertEquals(k, "male", Voices.byKey(k)!!.gender)
        for (k in listOf("boss", "employee", "azan", "motivational_coach", "child_baby")) assertEquals(k, "", Voices.byKey(k)!!.gender)
        assertEquals("Mother · English  ·  female", Voices.byKey("mother")!!.displayLabel)
        assertEquals("Boss · English", Voices.byKey("boss")!!.displayLabel)
    }

    @Test
    fun theAudioReadmeListsEveryMp3FileName() {
        val readme = listOf("src/main/assets/audio/README.txt", "app/src/main/assets/audio/README.txt")
            .map { File(it) }.firstOrNull { it.exists() }
        assertTrue("README.txt not found", readme != null)
        val text = readme!!.readText()
        for (k in voiceKeys + "default_file") assertTrue("README misses $k.mp3", text.contains("$k.mp3"))
    }
}
