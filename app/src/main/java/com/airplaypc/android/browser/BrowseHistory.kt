package com.airplaypc.android.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
)

class BrowseHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("beam_history", Context.MODE_PRIVATE)

    fun add(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("beam:")) return
        val list = load().toMutableList()
        list.removeAll { it.url == url }
        list.add(0, HistoryEntry(url, title.ifBlank { url }, System.currentTimeMillis()))
        while (list.size > 50) list.removeAt(list.lastIndex)
        save(list)
    }

    fun load(): List<HistoryEntry> {
        val raw = prefs.getString("entries", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        HistoryEntry(
                            url = o.optString("url"),
                            title = o.optString("title"),
                            timestamp = o.optLong("ts"),
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clear() {
        prefs.edit().putString("entries", "[]").apply()
    }

    private fun save(list: List<HistoryEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("url", e.url)
                    .put("title", e.title)
                    .put("ts", e.timestamp),
            )
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }
}
