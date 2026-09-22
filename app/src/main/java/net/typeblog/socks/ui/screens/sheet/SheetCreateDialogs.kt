package net.typeblog.socks.ui.screens.sheet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.typeblog.socks.R
import net.typeblog.socks.util.sheet.DGD_PASSWORD
import net.typeblog.socks.util.sheet.LOVE_PASSWORD
import net.typeblog.socks.util.sheet.SheetPreset
import net.typeblog.socks.util.sheet.SheetRow
import net.typeblog.socks.util.sheet.SheetStore
import net.typeblog.socks.util.sheet.SheetDb
import net.typeblog.socks.util.sheet.extractCUser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

data class UploadDraft(
    val name: String,
    val rows: List<SheetRow>,
    val dataCount: Int,
    val has2fa: Boolean,
    val pageHint: Boolean,
    val loveHint: Boolean
)

data class PwAsk(
    val preset: SheetPreset,
    val upload: UploadDraft?
)

private val PRESET_DESC = mapOf(
    SheetPreset.COOKIE to "cookies and uid",
    SheetPreset.COMBO to "cookies and 2fa and uid",
    SheetPreset.PAGE to "full columns"
)

fun presetTitle(p: SheetPreset): String = when (p) {
    SheetPreset.COOKIE -> "Cookie"
    SheetPreset.COMBO -> "2fa"
    SheetPreset.PAGE -> "Page"
}

@Composable
fun FabMenuPopup(
    onDismiss: () -> Unit,
    onPickPreset: (SheetPreset) -> Unit,
    onPickUpload: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(240.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(4.dp)
    ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_ss_facebook),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Facebook",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            for (p in SheetPreset.values()) {
                CreateOptionRow(
                    title = presetTitle(p),
                    desc = PRESET_DESC[p] ?: "",
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            PresetIcon(preset = p, sizeDp = 15)
                        }
                    },
                    onClick = { onPickPreset(p) }
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            CreateOptionRow(
                title = "Upload xlsx",
                desc = "Import data from file",
                icon = {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_ss_upload),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                },
                onClick = onPickUpload
            )
        }
}

@Composable
fun CreateFileMenuDialog(
    onDismiss: () -> Unit,
    onPickPreset: (SheetPreset) -> Unit,
    onPickUpload: () -> Unit
) {
    FabMenuPopup(onDismiss = onDismiss, onPickPreset = onPickPreset, onPickUpload = onPickUpload)
}

@Composable
fun TypePickDialog(
    draft: UploadDraft,
    onDismiss: () -> Unit,
    onPick: (SheetPreset) -> Unit
) {
    SheetModal(onDismiss = onDismiss, widthDp = 300, title = "Choose file type") {
        Column(modifier = Modifier.fillMaxWidth()) {
            TypeOptionRow(
                title = "Cookie",
                desc = "cookies and uid",
                preset = SheetPreset.COOKIE,
                detected = !draft.has2fa,
                disabled = draft.has2fa,
                disabledReason = "File has 2FA data - pick 2fa or Page",
                onClick = { onPick(SheetPreset.COOKIE) }
            )
            TypeOptionRow(
                title = "2fa",
                desc = "cookies and 2fa and uid",
                preset = SheetPreset.COMBO,
                detected = draft.has2fa,
                disabled = false,
                disabledReason = null,
                onClick = { onPick(SheetPreset.COMBO) }
            )
            TypeOptionRow(
                title = "Page",
                desc = "full columns",
                preset = SheetPreset.PAGE,
                detected = draft.pageHint,
                disabled = false,
                disabledReason = null,
                onClick = { onPick(SheetPreset.PAGE) }
            )
        }
    }
}

@Composable
fun PasswordPickDialog(
    upload: UploadDraft?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    SheetModal(onDismiss = onDismiss, widthDp = 340, title = "Pick a password") {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Files under these passwords are shared by default.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(12.dp))
            PasswordOptionRow(
                password = DGD_PASSWORD,
                detected = upload != null && upload.loveHint == false,
                onClick = { onPick(DGD_PASSWORD) }
            )
            PasswordOptionRow(
                password = LOVE_PASSWORD,
                detected = upload != null && upload.loveHint == true,
                onClick = { onPick(LOVE_PASSWORD) }
            )
        }
    }
}

@Composable
fun RenameFileDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    SheetModal(onDismiss = onDismiss, widthDp = 320, title = "Rename file") {
        Column(modifier = Modifier.fillMaxWidth()) {
            SheetNameInput(value = value, onValueChange = onValueChange, onDone = onConfirm)
            Spacer(modifier = Modifier.size(12.dp))
            SheetModalFooter(onCancel = onDismiss, onConfirm = onConfirm, confirmText = "Rename")
        }
    }
}

@Composable
fun ArchiveSingleDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    SheetConfirm(
        onDismiss = onDismiss,
        message = "Move this file to archive?",
        actionText = "Archive",
        onConfirm = onConfirm
    )
}

@Composable
fun ArchiveBulkDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    SheetConfirm(
        onDismiss = onDismiss,
        message = "Move " + count + " file" + (if (count > 1) "s" else "") + " to archive?",
        actionText = "Archive",
        onConfirm = onConfirm
    )
}

@Composable
private fun CreateOptionRow(
    title: String,
    desc: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TypeOptionRow(
    title: String,
    desc: String,
    preset: SheetPreset,
    detected: Boolean,
    disabled: Boolean,
    disabledReason: String?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .alpha(if (disabled) 0.45f else 1f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                PresetIcon(preset = preset, sizeDp = 15)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (disabled) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (detected) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                                .background(AliveGreen.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Detected",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AliveGreen
                            )
                        }
                    }
                }
                Text(
                    text = if (disabled && disabledReason != null) disabledReason else desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PasswordOptionRow(
    password: String,
    detected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PasswordBadge(password = password, sizeDp = 18)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = password,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (detected) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .background(AliveGreen.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Detected from file",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AliveGreen
                    )
                }
            }
        }
    }
}

fun importDraft(
    appCtx: Context,
    store: SheetStore,
    preset: SheetPreset,
    password: String,
    draft: UploadDraft
): String {
    val created = store.createFile(preset, password)
    val cols = preset.columns
    val rows = draft.rows.mapIndexed { i, r ->
        SheetRow(
            rowIdx = i,
            cookies = r.cookies,
            twofakey = if (cols.any { it.key == "twofakey" }) r.twofakey else "",
            uid = r.uid
        )
    }
    val db = SheetDb(appCtx)
    db.tx { d ->
        db.saveAllRows(d, created.id, rows)
        db.recordOp(d, created.id, "import")
    }
    store.refresh()
    val n = rows.count { it.isData(cols) }
    return "Successfully imported " + n + " rows."
}

fun parseUpload(appCtx: Context, uri: Uri): UploadDraft? {
    val name = queryName(appCtx, uri) ?: "upload.xlsx"
    if (!name.lowercase().endsWith(".xlsx") && !name.lowercase().endsWith(".xls")) {
        return null
    }
    val bytes = appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: return null
    val table = parseXlsxTable(bytes) ?: return null
    if (table.isEmpty()) return null
    val headerIdx = table.indexOfFirst { row ->
        row.any { cell ->
            val c = cell.trim().lowercase()
            c.contains("cookie") || c.contains("twofa") || c == "2fa" || c.contains("2 fa") || c == "uid"
        }
    }
    var cookieCol = 0
    var twofaCol = 1
    var uidCol = 2
    var dataStart = 0
    if (headerIdx >= 0) {
        val header = table[headerIdx].map { it.trim().lowercase() }
        for ((i, h) in header.withIndex()) {
            when {
                h.contains("cookie") -> cookieCol = i
                h.contains("twofa") || h == "2fa" || h.contains("2 fa") -> twofaCol = i
                h == "uid" -> uidCol = i
            }
        }
        dataStart = headerIdx + 1
    }
    val rows = mutableListOf<SheetRow>()
    var idx = 0
    for (r in dataStart until table.size) {
        val cells = table[r]
        fun cellAt(i: Int): String = if (i < cells.size) cells[i].trim() else ""
        val cookies = cellAt(cookieCol)
        val twofakey = cellAt(twofaCol)
        var uid = cellAt(uidCol)
        // Import parity with cell entry: fill a blank uid from the cookie's
        // c_user so imported rows are checkable without manual fix-up.
        if (uid.isEmpty() && cookies.isNotEmpty()) {
            uid = extractCUser(cookies) ?: ""
        }
        if (cookies.isEmpty() && twofakey.isEmpty() && uid.isEmpty()) continue
        rows.add(SheetRow(rowIdx = idx, cookies = cookies, twofakey = twofakey, uid = uid))
        idx++
    }
    if (rows.isEmpty()) return null
    val lower = name.lowercase()
    return UploadDraft(
        name = name.substringBeforeLast("."),
        rows = rows,
        dataCount = rows.size,
        has2fa = rows.any { it.twofakey.isNotEmpty() },
        pageHint = lower.contains("page"),
        loveHint = lower.contains("love")
    )
}

private fun queryName(appCtx: Context, uri: Uri): String? {
    return try {
        appCtx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i < 0) return null
            c.getString(i)
        }
    } catch (e: Exception) {
        null
    }
}

private fun parseXlsxTable(bytes: ByteArray): List<List<String>>? {
    var sharedXml: ByteArray? = null
    var sheetXml: ByteArray? = null
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var e = zip.nextEntry
        while (e != null) {
            val n = e.name
            if (n == "xl/sharedStrings.xml") {
                sharedXml = readAll(zip)
            } else if (sheetXml == null && n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) {
                sheetXml = readAll(zip)
            }
            zip.closeEntry()
            e = zip.nextEntry
        }
    }
    val sheet = sheetXml ?: return null
    val shared = sharedXml?.let { parseSharedStrings(it) } ?: emptyList()
    return parseSheet(sheet, shared)
}

private fun readAll(zip: ZipInputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = zip.read(buf)
        if (n <= 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private fun parseSharedStrings(xml: ByteArray): List<String> {
    val out = mutableListOf<String>()
    val p = Xml.newPullParser()
    p.setInput(ByteArrayInputStream(xml), "UTF-8")
    var inItem = false
    var inText = false
    val buf = StringBuilder()
    var event = p.eventType
    while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
        when (event) {
            org.xmlpull.v1.XmlPullParser.START_TAG -> {
                when (p.name) {
                    "si" -> {
                        inItem = true
                        buf.clear()
                    }
                    "t" -> if (inItem) inText = true
                }
            }
            org.xmlpull.v1.XmlPullParser.TEXT -> if (inText) buf.append(p.text)
            org.xmlpull.v1.XmlPullParser.END_TAG -> {
                when (p.name) {
                    "t" -> inText = false
                    "si" -> {
                        inItem = false
                        out.add(buf.toString())
                    }
                }
            }
        }
        event = p.next()
    }
    return out
}

private fun colRefToIndex(ref: String?): Int {
    if (ref == null) return -1
    var n = 0
    var has = false
    for (ch in ref) {
        if (ch.isLetter()) {
            has = true
            n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        } else {
            break
        }
    }
    return if (has) n - 1 else -1
}

private fun parseSheet(xml: ByteArray, shared: List<String>): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val p = Xml.newPullParser()
    p.setInput(ByteArrayInputStream(xml), "UTF-8")
    var cur: MutableList<String>? = null
    var cellType: String? = null
    var cellCol = -1
    var reading = false
    val buf = StringBuilder()
    var event = p.eventType
    while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
        when (event) {
            org.xmlpull.v1.XmlPullParser.START_TAG -> {
                when (p.name) {
                    "row" -> cur = mutableListOf()
                    "c" -> {
                        cellType = p.getAttributeValue(null, "t")
                        cellCol = colRefToIndex(p.getAttributeValue(null, "r"))
                        buf.clear()
                    }
                    "v", "t" -> {
                        reading = true
                        buf.clear()
                    }
                }
            }
            org.xmlpull.v1.XmlPullParser.TEXT -> if (reading) buf.append(p.text)
            org.xmlpull.v1.XmlPullParser.END_TAG -> {
                when (p.name) {
                    "v", "t" -> reading = false
                    "c" -> {
                        val row = cur
                        if (row != null) {
                            val raw = buf.toString()
                            val value = if (cellType == "s") {
                                shared.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
                            } else {
                                raw
                            }
                            if (cellCol >= 0) {
                                while (row.size <= cellCol) row.add("")
                                row[cellCol] = value
                            } else {
                                row.add(value)
                            }
                        }
                        buf.clear()
                    }
                    "row" -> {
                        val row = cur
                        if (row != null) {
                            rows.add(row.toList())
                            cur = null
                        }
                    }
                }
            }
        }
        event = p.next()
    }
    return rows
}
