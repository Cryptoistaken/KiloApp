package net.typeblog.socks.util

import android.content.Context

/**
 * Random-name pool for the floating circle menu's Name bubble.
 * One name per line in `assets/names.txt` — edit that file to change
 * the pool, no code changes needed. Falls back to a built-in trio
 * when the asset is missing or empty.
 */
object NamesRepo {
    private var cached: List<String>? = null

    private val FALLBACK = listOf("Amina Diallo", "Kwame Mensah", "Fatou Ndiaye")

    fun all(context: Context): List<String> {
        cached?.let { return it }
        val loaded = try {
            context.assets.open("names.txt").bufferedReader().useLines { seq ->
                seq.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        val result = if (loaded.isEmpty()) FALLBACK else loaded
        cached = result
        return result
    }

    fun random(context: Context): String = all(context).random()
}
