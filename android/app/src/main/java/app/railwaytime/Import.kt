package app.railwaytime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import app.railwaytime.core.BoardParser
import app.railwaytime.core.OcrLine
import app.railwaytime.core.OcrRows
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Backup way in that always works: open IVRM in Chrome, then share a screenshot (or the
 * copied page text) to Railway Time. Text is read on the phone; nothing is uploaded.
 */
object Importer {
    /** Saves the board if [text] contains one. */
    fun importText(context: Context, text: String, source: String): Boolean {
        val store = Store(context)
        if (!BoardParser.looksLikeBoard(text)) {
            store.log("$source: no board found (${text.length} chars)")
            return false
        }
        store.saveBoard(text, url = null)
        store.log("$source: board imported")
        Widget.updateAll(context)
        return true
    }

    /** Reads a screenshot with on-device text recognition, then [importText]. */
    fun importImage(context: Context, uri: Uri, done: (Boolean) -> Unit) {
        val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrElse {
            Store(context).log("screenshot: couldn't open image (${it.message})")
            done(false)
            return
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box: Rect = line.boundingBox ?: return@mapNotNull null
                    OcrLine(line.text, box.left, box.top, box.right, box.bottom)
                }
                done(importText(context, OcrRows.toText(lines), "screenshot"))
            }
            .addOnFailureListener {
                Store(context).log("screenshot: text recognition failed (${it.message})")
                done(false)
            }
    }

    fun toast(context: Context, ok: Boolean) = Toast.makeText(
        context,
        if (ok) "Board imported ✓" else "Couldn't find the crew board in that. Use a screenshot of the IVRM page with Board Position showing.",
        Toast.LENGTH_LONG,
    ).show()
}

/** Receives "Share → Railway Time" from Chrome, Photos or the screenshot preview. */
class ImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val image = streamUri(intent)
        when {
            image != null -> Importer.importImage(this, image) { finishWith(it) }
            text != null -> finishWith(Importer.importText(this, text, "shared text"))
            else -> finishWith(false)
        }
    }

    private fun finishWith(ok: Boolean) {
        Importer.toast(this, ok)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
}
