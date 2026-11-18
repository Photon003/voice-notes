package com.photon.voicenotes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Note(val id: Long, val language: String, var text: String)

/** Saves notes in a small JSON file inside the app (never leaves the phone). */
class NotesStore(context: Context) {

    private val file = File(context.filesDir, "notes.json")
    val notes: MutableList<Note> = load()

    private fun load(): MutableList<Note> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Note(o.getLong("id"), o.optString("lang"), o.getString("text"))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save() {
        val arr = JSONArray()
        for (n in notes) {
            arr.put(JSONObject().put("id", n.id).put("lang", n.language).put("text", n.text))
        }
        file.writeText(arr.toString())
    }

    fun add(note: Note) {
        notes.add(0, note)
        save()
    }

    fun remove(note: Note) {
        notes.remove(note)
        save()
    }
}
