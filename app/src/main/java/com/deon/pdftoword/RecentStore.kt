package com.deon.pdftoword

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentItem(val name: String, val fileName: String, val timeMs: Long)

/** Recent conversions persisted in SharedPreferences (max 20). */
object RecentStore {
    private const val PREFS = "pdftoword_recents"
    private const val KEY = "items"
    private const val MAX = 20

    fun add(ctx: Context, name: String, fileName: String) {
        val items = list(ctx).toMutableList()
        items.removeAll { it.fileName == fileName }
        items.add(0, RecentItem(name, fileName, System.currentTimeMillis()))
        val trimmed = items.take(MAX)
        val arr = JSONArray()
        for (it in trimmed) {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("file", it.fileName)
                    .put("time", it.timeMs)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun list(ctx: Context): List<RecentItem> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RecentItem(o.getString("name"), o.getString("file"), o.getLong("time"))
            }
        }.getOrDefault(emptyList())
    }

    fun remove(ctx: Context, fileName: String) {
        val items = list(ctx).filter { it.fileName != fileName }
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("file", it.fileName)
                    .put("time", it.timeMs)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
