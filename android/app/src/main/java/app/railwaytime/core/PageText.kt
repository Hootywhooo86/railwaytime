package app.railwaytime.core

/**
 * Turns the IVRM page into plain text: table cells end with a tab, block
 * elements end with a newline. Text in collapsed sections is kept.
 * The parser only looks at the resulting cells, so the exact layout doesn't matter.
 */
object PageText {
    /** Runs inside the WebView; returns the page as text. */
    const val EXTRACT_JS = """
(function(){
  var BLOCK={DIV:1,P:1,TR:1,LI:1,UL:1,OL:1,TABLE:1,TBODY:1,THEAD:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,
    SECTION:1,HEADER:1,FOOTER:1,FORM:1,FIELDSET:1,LEGEND:1,DL:1,DT:1,DD:1,BR:1,HR:1,A:1,BUTTON:1};
  var SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,TEMPLATE:1,SVG:1,SELECT:1};
  var out=[];
  function walk(n){
    if(n.nodeType===3){out.push(n.nodeValue.replace(/\s+/g,' '));return;}
    if(n.nodeType!==1)return;
    var t=n.tagName.toUpperCase();
    if(SKIP[t])return;
    if(BLOCK[t])out.push('\n');
    for(var c=n.firstChild;c;c=c.nextSibling)walk(c);
    if(t==='IFRAME'){try{walk(n.contentDocument.body);}catch(e){}}
    if(t==='TD'||t==='TH')out.push('\t');
    if(BLOCK[t])out.push('\n');
  }
  if(document.body)walk(document.body);
  return out.join('');
})()
"""

    private val BLOCK_TAGS = Regex(
        """<\s*/?\s*(div|p|tr|li|ul|ol|table|tbody|thead|h[1-6]|section|header|footer|form|fieldset|legend|dl|dt|dd|br|hr|a|button)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val CELL_END = Regex("""<\s*/\s*t[dh]\s*>""", RegexOption.IGNORE_CASE)
    private val DROP = Regex("""<(script|style|noscript|template|select)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("""<[^>]*>""")

    /** Same idea as [EXTRACT_JS], for raw HTML. */
    fun fromHtml(html: String): String = html
        .replace(DROP, "")
        .replace(Regex("""\s+"""), " ")
        .replace(CELL_END, "\t")
        .replace(BLOCK_TAGS, "\n")
        .replace(TAG, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")

    /** Non-blank cells in reading order, whitespace collapsed. */
    fun tokens(text: String): List<String> = text
        .split('\n', '\t')
        .map { it.replace(' ', ' ').replace(Regex("""\s+"""), " ").trim() }
        .filter { it.isNotEmpty() }
}
