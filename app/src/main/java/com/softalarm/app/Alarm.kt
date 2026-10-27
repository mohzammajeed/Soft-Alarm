package com.softalarm.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// days: 0 = Sunday ... 6 = Saturday. Empty list = ring once.
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val label: String,
    val voice: String,
    val days: List<Int>,
    var enabled: Boolean,
    val customUri: String = ""   // only used when voice == "custom"
)

object AlarmStore {
    private const val PREFS = "softalarm"
    private const val KEY = "alarms"

    fun load(ctx: Context): MutableList<Alarm> {
        val list = mutableListOf<Alarm>()
        try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = o.getJSONArray("days")
                val days = mutableListOf<Int>()
                for (j in 0 until d.length()) days.add(d.getInt(j))
                list.add(
                    Alarm(
                        o.getInt("id"), o.getInt("hour"), o.getInt("minute"),
                        o.optString("label", ""), Voices.normalize(o.optString("voice", "default")),
                        days, o.optBoolean("enabled", true), o.optString("customUri", "")
                    )
                )
            }
        } catch (e: Exception) {
            // nothing saved yet or unreadable: start with an empty list
        }
        return list
    }

    fun save(ctx: Context, list: List<Alarm>) {
        val arr = JSONArray()
        for (a in list) {
            val o = JSONObject()
            o.put("id", a.id)
            o.put("hour", a.hour)
            o.put("minute", a.minute)
            o.put("label", a.label)
            o.put("voice", a.voice)
            o.put("days", JSONArray(a.days))
            o.put("enabled", a.enabled)
            o.put("customUri", a.customUri)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun newId(): Int = ((System.currentTimeMillis() / 10) % 1_000_000_000L).toInt()
}
