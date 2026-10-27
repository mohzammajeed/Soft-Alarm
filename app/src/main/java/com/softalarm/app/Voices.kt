package com.softalarm.app

// pro = true means the voice needs the one-time Pro unlock.
// gender is "female", "male" or "" (not a person / not stated).
data class Voice(val key: String, val label: String, val pro: Boolean, val gender: String = "") {
    // Where the mp3 goes: app/src/main/assets/audio/<key>.mp3
    val assetPath: String get() = "audio/$key.mp3"

    // Text shown in the voice picker
    val displayLabel: String get() = if (gender.isEmpty()) label else "$label  ·  $gender"
}

// NAMING SYSTEM (yours): <voice>.mp3 is English, <voice>_pa.mp3 Punjabi, <voice>_ur.mp3 Urdu, <voice>_hi.mp3 Hindi.
//   e.g. mother.mp3, mother_pa.mp3, mother_ur.mp3, mother_hi.mp3, motivational_coach_hi.mp3, child_baby_ur.mp3
//
// FREE for everyone:
//   - "Phone alarm tone"      (key: default)       the phone's own alarm sound, no file needed
//   - "SoftAlarm soft tune"   (key: default_file)  your default mp3: audio/default_file.mp3
//   - "My own file"           (key: custom)        any audio file the user picks from the phone
//   - the 12 voices below, in 4 languages each (48 files)
// PRO: none yet. To add a Pro voice later, add one more line like the ones below with the third value
//   changed from false to true, and drop its mp3 into assets/audio. The Unlock Pro button then appears by itself.
// If an mp3 is missing, the alarm plays the phone's normal alarm tone instead.
object Voices {
    val all: List<Voice> = listOf(
        Voice("default", "Phone alarm tone (default)", false),
        Voice("default_file", "SoftAlarm soft tune (default file)", false),
        Voice("custom", "My own file (from phone)", false),
        Voice("mother", "Mother · English", false, "female"),
        Voice("mother_pa", "Mother · Punjabi", false, "female"),
        Voice("mother_ur", "Mother · Urdu", false, "female"),
        Voice("mother_hi", "Mother · Hindi", false, "female"),
        Voice("father", "Father · English", false, "male"),
        Voice("father_pa", "Father · Punjabi", false, "male"),
        Voice("father_ur", "Father · Urdu", false, "male"),
        Voice("father_hi", "Father · Hindi", false, "male"),
        Voice("sister", "Sister · English", false, "female"),
        Voice("sister_pa", "Sister · Punjabi", false, "female"),
        Voice("sister_ur", "Sister · Urdu", false, "female"),
        Voice("sister_hi", "Sister · Hindi", false, "female"),
        Voice("brother", "Brother · English", false, "male"),
        Voice("brother_pa", "Brother · Punjabi", false, "male"),
        Voice("brother_ur", "Brother · Urdu", false, "male"),
        Voice("brother_hi", "Brother · Hindi", false, "male"),
        Voice("grandmother", "Grandmother · English", false, "female"),
        Voice("grandmother_pa", "Grandmother · Punjabi", false, "female"),
        Voice("grandmother_ur", "Grandmother · Urdu", false, "female"),
        Voice("grandmother_hi", "Grandmother · Hindi", false, "female"),
        Voice("husband", "Husband · English", false, "male"),
        Voice("husband_pa", "Husband · Punjabi", false, "male"),
        Voice("husband_ur", "Husband · Urdu", false, "male"),
        Voice("husband_hi", "Husband · Hindi", false, "male"),
        Voice("wife", "Wife · English", false, "female"),
        Voice("wife_pa", "Wife · Punjabi", false, "female"),
        Voice("wife_ur", "Wife · Urdu", false, "female"),
        Voice("wife_hi", "Wife · Hindi", false, "female"),
        Voice("motivational_coach", "Motivational Coach · English", false),
        Voice("motivational_coach_pa", "Motivational Coach · Punjabi", false),
        Voice("motivational_coach_ur", "Motivational Coach · Urdu", false),
        Voice("motivational_coach_hi", "Motivational Coach · Hindi", false),
        Voice("boss", "Boss · English", false),
        Voice("boss_pa", "Boss · Punjabi", false),
        Voice("boss_ur", "Boss · Urdu", false),
        Voice("boss_hi", "Boss · Hindi", false),
        Voice("employee", "Employee · English", false),
        Voice("employee_pa", "Employee · Punjabi", false),
        Voice("employee_ur", "Employee · Urdu", false),
        Voice("employee_hi", "Employee · Hindi", false),
        Voice("azan", "Azan · English", false),
        Voice("azan_pa", "Azan · Punjabi", false),
        Voice("azan_ur", "Azan · Urdu", false),
        Voice("azan_hi", "Azan · Hindi", false),
        Voice("child_baby", "Child / Little Baby · English", false),
        Voice("child_baby_pa", "Child / Little Baby · Punjabi", false),
        Voice("child_baby_ur", "Child / Little Baby · Urdu", false),
        Voice("child_baby_hi", "Child / Little Baby · Hindi", false)
    )

    fun byKey(key: String): Voice? = all.firstOrNull { it.key == key }
    fun isPro(key: String): Boolean = byKey(key)?.pro ?: false
    fun labelFor(key: String): String = byKey(key)?.label ?: "Phone alarm tone (default)"

    // True when at least one voice needs Pro. With none, the app hides everything about buying.
    fun hasProVoices(): Boolean = all.any { it.pro }

    // An alarm saved with a voice that no longer exists (an older version of the app) falls back to the phone tone
    fun normalize(key: String): String = if (byKey(key) != null) key else "default"
}
