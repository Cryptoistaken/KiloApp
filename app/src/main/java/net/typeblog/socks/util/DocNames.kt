package net.typeblog.socks.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Display names for user-picked document uris, in one home so the restore
 * confirmation and the sheet upload reader cannot drift apart.
 *
 * A picked file has to be asked of the provider, not read off the uri: a
 * MediaStore document uri ends in its numeric row id, so the name cannot be
 * parsed out of it.
 */
object DocNames {

    /**
     * The provider's name for [uri], or null when the provider will not answer
     * or has no name. Blocking content resolver query, so call off the main
     * thread.
     */
    @JvmStatic
    fun query(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i < 0) return null
            c.getString(i)?.takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * [query] first, then the last uri segment for providers that do not
     * answer, then [fallback] for when neither yields anything. Blocking, so
     * call off the main thread.
     */
    @JvmStatic
    fun display(ctx: Context, uri: Uri, fallback: String): String =
        query(ctx, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: fallback
}
