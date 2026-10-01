package com.aiinterviewer.coach.data

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream

/** The two shapes a CV can arrive in. */
sealed interface CvImport {
    /** A plain-text CV, already readable. */
    data class Text(val text: String) : CvImport

    /** PDF pages rendered to JPEG and base64-encoded, for the vision model. */
    data class Pages(val base64Jpeg: List<String>) : CvImport
}

/**
 * Reads a CV without any third-party library.
 *
 * Text files are read directly. PDFs are rendered to images with Android's
 * built-in PdfRenderer and handed to DeepSeek Flash, which can read images and
 * returns the profile as text. That replaces a PDF-parsing dependency entirely.
 *
 * Word documents are not supported; export to PDF first.
 */
object CvImporter {

    private const val MAX_PAGES = 8
    private const val MAX_WIDTH_PX = 1600
    private const val JPEG_QUALITY = 80

    fun read(context: Context, uri: Uri, fileName: String): CvImport {
        val lowerName = fileName.lowercase()
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        val isPdf = lowerName.endsWith(".pdf") || mime.contains("pdf")

        if (isPdf) return CvImport.Pages(renderPdfPages(context, uri))

        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: ""
        return CvImport.Text(text)
    }

    /** Best-effort human-readable filename for display in the UI. */
    fun displayName(context: Context, uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, null, null, null, null)
            val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
            if (cursor != null && cursor.moveToFirst() && nameIndex >= 0) {
                cursor.getString(nameIndex)
            } else {
                uri.lastPathSegment.orEmpty()
            }
        } catch (t: Throwable) {
            uri.lastPathSegment.orEmpty()
        } finally {
            cursor?.close()
        }
    }

    private fun renderPdfPages(context: Context, uri: Uri): List<String> {
        val encoded = mutableListOf<String>()
        val descriptor: ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(uri, "r") ?: return encoded

        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(descriptor)
            val pageCount = minOf(renderer.pageCount, MAX_PAGES)
            for (index in 0 until pageCount) {
                var page: PdfRenderer.Page? = null
                var bitmap: Bitmap? = null
                try {
                    page = renderer.openPage(index)
                    val scale = if (page.width > MAX_WIDTH_PX) {
                        MAX_WIDTH_PX.toFloat() / page.width.toFloat()
                    } else {
                        1f
                    }
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)

                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // PDF pages are transparent by default, which renders as black
                    // once flattened to JPEG.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val buffer = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)
                    encoded.add(Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP))
                } catch (t: Throwable) {
                    // Skip an unreadable page rather than losing the whole CV.
                } finally {
                    bitmap?.recycle()
                    try {
                        page?.close()
                    } catch (t: Throwable) {
                        // ignored
                    }
                }
            }
        } catch (t: Throwable) {
            // Falls through with whatever pages were rendered.
        } finally {
            try {
                renderer?.close()
            } catch (t: Throwable) {
                // ignored
            }
            try {
                descriptor.close()
            } catch (t: Throwable) {
                // ignored
            }
        }
        return encoded
    }
}
