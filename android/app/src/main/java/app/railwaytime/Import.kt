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
 * copied page text) to When Do I Work. Text is read on the phone; nothing is uploaded.
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
        Duty.checkWorking(context)
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

/**
 * The Chrome shortcut ("wdiw" bookmark). Run on the IVRM page in Chrome — where CN lets you in —
 * it reads the page (collapsed sections too), keeps the part up to Train Lineup, copies it, and
 * hands it to this app through an intent link. Nothing goes anywhere else.
 */
object ChromeShortcut {
    const val NAME = "wdiw"

    const val BOOKMARKLET = "javascript:(function(){" +
        "var B={DIV:1,P:1,TR:1,LI:1,UL:1,OL:1,TABLE:1,TBODY:1,THEAD:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,SECTION:1,HEADER:1,FOOTER:1,FORM:1,FIELDSET:1,LEGEND:1,DL:1,DT:1,DD:1,BR:1,HR:1,A:1,BUTTON:1}," +
        "S={SCRIPT:1,STYLE:1,NOSCRIPT:1,TEMPLATE:1,SVG:1,SELECT:1},o=[];" +
        "function w(n){if(n.nodeType==3){o.push(n.nodeValue.replace(/\\s+/g,' '));return}if(n.nodeType!=1)return;" +
        "var t=n.tagName.toUpperCase();if(S[t])return;if(B[t])o.push('\\n');" +
        "for(var c=n.firstChild;c;c=c.nextSibling)w(c);if(t=='TD'||t=='TH')o.push('\\t');if(B[t])o.push('\\n')}" +
        "w(document.body);var x=o.join('').replace(/\\n[ \\n]*\\n/g,'\\n'),i=x.indexOf('Train Lineup');if(i>0)x=x.slice(0,i);" +
        "if(x.indexOf('Board Position')<0){alert('Open the IVRM Mobile Crew page first, then tap wdiw.');return}" +
        "try{navigator.clipboard.writeText(x)}catch(e){}" +
        "location.href='intent://import#Intent;scheme=whendoiwork;package=app.railwaytime;S.board='+encodeURIComponent(x)+';end'" +
        "})()"
}

/** Receives "Share → When Do I Work" from Chrome, Photos or the screenshot preview. */
class ImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // From the Chrome shortcut: whendoiwork://import with the page text in "board".
        val fromShortcut = intent.takeIf { it.action == Intent.ACTION_VIEW }?.let { it.getStringExtra("board") ?: it.data?.getQueryParameter("board") }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val image = streamUri(intent)
        when {
            fromShortcut != null -> finishWith(Importer.importText(this, fromShortcut, "Chrome shortcut"))
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
