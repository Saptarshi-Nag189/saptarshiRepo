package com.apkguard.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.apkguard.core.Report
import com.apkguard.core.ReportExport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/** Short summaries of past scans. Reports themselves are not kept; export one to save it. */
object History {
    class Entry(val name: String, val pkg: String?, val score: Int, val verdict: String, val time: Long)

    private const val MAX = 30

    fun add(ctx: Context, e: Entry) {
        val list = load(ctx).toMutableList()
        list.add(0, e)
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(JSONObject().put("name", it.name).put("pkg", it.pkg ?: JSONObject.NULL)
                .put("score", it.score).put("verdict", it.verdict).put("time", it.time))
        }
        prefs(ctx).edit().putString("items", arr.toString()).apply()
    }

    fun load(ctx: Context): List<Entry> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("items", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("name"), o.optString("pkg").takeIf { !o.isNull("pkg") }, o.getInt("score"), o.getString("verdict"), o.getLong("time"))
        }
    }.getOrDefault(emptyList())

    fun clear(ctx: Context) = prefs(ctx).edit().remove("items").apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)
}

/** Writes exported reports. */
object ReportFiles {
    enum class Format(val label: String, val note: String, val ext: String, val mime: String) {
        PDF("PDF", "Easy to read and print", "pdf", "application/pdf"),
        HTML("Web page", "Opens in any browser", "html", "text/html"),
        JSON("JSON", "For other tools and scripts", "json", "application/json"),
        TEXT("Plain text", "Paste into a chat or email", "txt", "text/plain"),
    }

    fun fileName(r: Report, date: String, f: Format): String {
        val pkg = (r.info.packageName ?: "unknown").replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return "apkguard-$pkg-$date.${f.ext}"
    }

    fun write(out: OutputStream, r: Report, m: ReportExport.Meta, f: Format, evidence: Boolean) {
        when (f) {
            Format.TEXT -> out.write(ReportExport.text(r, m, evidence).toByteArray())
            Format.JSON -> out.write(ReportExport.json(r, m, evidence).toByteArray())
            Format.HTML -> out.write(ReportExport.html(r, m, evidence).toByteArray())
            Format.PDF -> pdf(out, ReportExport.text(r, m, evidence))
        }
    }

    /** A4 PDF using Android's built-in PdfDocument; no library needed. */
    private fun pdf(out: OutputStream, body: String) {
        val doc = PdfDocument()
        val pageW = 595
        val pageH = 842
        val margin = 48
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 28, 32); textSize = 10.5f }
        val layout = StaticLayout.Builder.obtain(body, 0, body.length, paint, pageW - margin * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
        var line = 0
        var pageNo = 1
        while (line < layout.lineCount) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            val c: Canvas = page.canvas
            val startTop = layout.getLineTop(line)
            var end = line
            while (end < layout.lineCount && layout.getLineBottom(end) - startTop <= pageH - margin * 2) end++
            if (end == line) end = line + 1
            c.save()
            c.translate(margin.toFloat(), (margin - startTop).toFloat())
            c.clipRect(0, startTop, pageW - margin * 2, layout.getLineBottom(end - 1))
            layout.draw(c)
            c.restore()
            doc.finishPage(page)
            line = end
            pageNo++
        }
        doc.writeTo(out)
        doc.close()
    }

    fun shareDir(ctx: Context) = File(ctx.cacheDir, "reports").apply { mkdirs() }
}

/**
 * Serves exported reports to the app the user shares with. Not exported; access is only
 * through a one-off URI grant on the share intent. Read-only, flat directory, no paths.
 */
class ReportProvider : ContentProvider() {
    companion object { const val AUTHORITY = "com.apkguard.app.reports" }

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (uri.pathSegments.size != 1 || name.contains('/') || name.startsWith(".")) return null
        val dir = ReportFiles.shareDir(context ?: return null)
        val f = File(dir, name)
        return if (f.canonicalFile.parentFile == dir.canonicalFile && f.isFile) f else null
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        val f = fileFor(uri) ?: throw java.io.FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun getType(uri: Uri): String? = ReportFiles.Format.entries.firstOrNull { uri.lastPathSegment?.endsWith(".${it.ext}") == true }?.mime

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
