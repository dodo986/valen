/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The shapes lyrics arrive in, read into one [Lyrics]: TTML (Apple-style timed text, word spans
 * and backing vocals), Metrolist's MLX (as text and as its version 2 JSON, which Lyprio answers
 * in), LRC with or without word tags (`<mm:ss.xx>`), and plain text. What the readers accept
 * follows Metrolist's parsers (GPL-3.0, see NOTICE), condensed to what Valen shows: the words,
 * their timing, backing vocals and voices. Translations and romanisations are not read.
 */
object LyricsFormats {

    /**
     * [text] read as [format] ("ttml", "mlx", "lrc", "plain"; anything else, or null, is
     * recognised by its look). Null when nothing in it is a line of words.
     */
    fun parse(text: String, format: String?, source: String, durationMs: Long? = null): Lyrics? {
        if (text.isBlank() || text.length > MAX_CHARS) return null
        val kind = format?.lowercase()?.takeIf { it in KNOWN } ?: sniff(text)
        val lines = when (kind) {
            "ttml" -> Ttml.read(text)
            "mlx" -> if (text.trimStart().startsWith('{')) MlxJson.read(text) else readTagged(text, mlx = true)
            "lrc" -> readTagged(text, mlx = false)
            else -> plain(text)
        } ?: return null
        return finish(lines, source, durationMs)
    }

    /**
     * Lines a source read itself (a service's own lyrics), finished the same way as a document:
     * put in order, ends filled in, empty ones dropped, timing judged.
     */
    fun fromLines(lines: List<LyricLine>, source: String, durationMs: Long? = null): Lyrics? = finish(lines, source, durationMs)

    /** Lines with timing already read, put in order, ends filled in, empty ones dropped. */
    internal fun finish(raw: List<LyricLine>, source: String, durationMs: Long?): Lyrics? {
        val timed = raw.any { it.startMs > 0 || it.timedByWord }
        var lines = if (timed) raw.sortedBy { it.startMs } else raw
        lines = attachBacking(lines)
        // A line without an end lasts until the next line begins (or the song ends).
        lines = lines.mapIndexed { i, line ->
            if (!timed) return@mapIndexed line.copy(startMs = 0, endMs = 0)
            val next = lines.drop(i + 1).firstOrNull { it.startMs > line.startMs }?.startMs
            val wordsEnd = (line.words + line.backing).filter { it.endMs != OPEN_END }.maxOfOrNull { it.endMs }
            val open = (line.words + line.backing).any { it.endMs == OPEN_END }
            val end = when {
                line.endMs > line.startMs -> line.endMs
                !open && wordsEnd != null && wordsEnd > line.startMs -> wordsEnd
                next != null -> next
                durationMs != null && durationMs > line.startMs -> durationMs
                else -> line.startMs + LAST_LINE_MS
            }
            fun List<LyricWord>.closed() = map { w -> if (w.endMs == OPEN_END) w.copy(endMs = minOf(end, w.startMs + OPEN_WORD_MS).coerceAtLeast(w.startMs)) else w }
            line.copy(endMs = end, words = line.words.closed(), backing = line.backing.closed())
        }.filter { it.text.isNotBlank() || it.backingText?.isNotBlank() == true }
        if (lines.isEmpty()) return null
        val timing = when {
            !timed -> LyricsTiming.None
            lines.count { it.timedByWord } * 2 >= lines.count { !it.aside } -> LyricsTiming.Words
            else -> LyricsTiming.Lines
        }
        // Word timing only where every timed line has it: a song half in words reads as broken.
        val even = if (timing == LyricsTiming.Lines) lines.map { it.copy(words = emptyList(), backing = emptyList()) } else lines
        return Lyrics(even, timing, source)
    }

    /**
     * A first line that only names the song ("Song - Artist", either way round) is a header, not
     * words: some sources put one there. Dropped when another line follows.
     */
    fun withoutHeader(lyrics: Lyrics, title: String, artist: String): Lyrics {
        val first = lyrics.lines.firstOrNull() ?: return lyrics
        if (first.timedByWord || lyrics.lines.size < 2) return lyrics
        fun norm(s: String) = s.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
        val t = norm(title)
        val a = norm(artist)
        if (t.isEmpty() || a.isEmpty()) return lyrics
        val line = norm(first.text)
        val header = listOf(" - ", " – ", " — ").any { line == "$t$it$a" || line == "$a$it$t" }
        return if (header) lyrics.copy(lines = lyrics.lines.drop(1)) else lyrics
    }

    /**
     * Whether a result for [resultArtist] can be the song by [wanted]: any of the names on one side
     * close to one on the other. Different scripts cannot be compared by spelling and pass.
     */
    fun artistsMatch(wanted: String, resultArtist: String): Boolean {
        fun names(s: String) = s.split(ARTIST_SPLIT).map { it.lowercase().replace(NON_LETTERS, " ").trim().removePrefix("the ").trim() }
            .filter { it.isNotEmpty() }.toSet()
        val a = names(wanted)
        val b = names(resultArtist)
        if (a.isEmpty() || b.isEmpty()) return true
        if ((a + b).any { n -> n.any { it.isLetter() && it.code > 0x24F } }) return true
        return a.any { x -> b.any { y -> similarity(x, y) >= 0.8 } }
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return 1.0 - previous[b.length].toDouble() / longest
    }

    private fun sniff(text: String): String {
        val head = text.trimStart()
        return when {
            head.startsWith('<') && (head.contains("<tt") || head.contains(":tt")) -> "ttml"
            head.startsWith('{') -> "mlx"
            head.startsWith("[meta]") || text.lineSequence().any { it.trim().equals("[lyrics]", ignoreCase = true) } -> "mlx"
            LINE_STAMP.containsMatchIn(text) -> "lrc"
            else -> "plain"
        }
    }

    private fun plain(text: String): List<LyricLine> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { LyricLine(0, 0, it) }.toList()

    /**
     * LRC and MLX text: `[mm:ss.xx]` (MLX: `[start~end|voice]`) before each line, word tags
     * `<mm:ss.xx>` inside it, `[bg:…]` for backing vocals sung under it, `{bg}` for a line that is
     * all backing vocals. In MLX only the `[lyrics]` section is read.
     */
    private fun readTagged(text: String, mlx: Boolean): List<LyricLine> {
        val rows = if (mlx) mlxLyricsSection(text) else text.lines()
        var offset = 0L
        val lines = mutableListOf<LyricLine>()
        // An LRC line with no words is where the line before it ends (an instrumental gap).
        val ends = mutableListOf<Long>()
        for (raw in rows) {
            val row = raw.trim()
            if (row.isEmpty() || row.startsWith("//")) continue
            OFFSET.matchEntire(row)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L; return@let }
            if (META.matches(row)) continue
            var rest = row
            val stamps = mutableListOf<Pair<Long, Long?>>()
            var voice: String? = null
            while (true) {
                val m = HEADER.matchAt(rest, 0) ?: break
                stamps += clock(m.groupValues[1]) to m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::clock)
                m.groupValues[3].takeIf { it.isNotBlank() }?.let { voice = it.trim() }
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            if (mlx && stamps.isEmpty() && SECTION.matches(row)) continue
            var aside = false
            if (rest.contains("{bg}")) {
                aside = true
                rest = rest.replace("{bg}", "")
            }
            LEGACY_VOICE.find(rest)?.let { voice = voice ?: it.groupValues[1].takeIf { v -> v != "bg" }; if (it.groupValues[1] == "bg") aside = true }
            rest = LEGACY_VOICE.replace(rest, "").let { r -> PREFIX_VOICE.find(r)?.let { voice = voice ?: it.groupValues[1]; r.substring(it.range.last + 1) } ?: r }
            val bg = BACKING.find(rest)
            val main = if (bg != null) rest.substring(0, bg.range.first) else rest
            val backingRaw = bg?.groupValues?.get(1)
            val lineStart = stamps.firstOrNull()?.first?.minus(offset)
            val (mainText, mainWords) = words(main, offset, lineStart, mlx)
            val (backText, backWords) = backingRaw?.let { words(it, offset, lineStart, mlx) } ?: ("" to emptyList())
            if (stamps.isEmpty()) {
                if (mainText.isNotBlank()) lines += LyricLine(0, 0, mainText, mainWords, backWords, backText.ifBlank { null }, voice, aside)
                continue
            }
            for ((start, end) in stamps) {
                val s = (start - offset).coerceAtLeast(0)
                if (mainText.isBlank() && backText.isBlank()) {
                    ends += s
                    continue
                }
                val first = mainWords.firstOrNull()?.startMs
                lines += LyricLine(
                    startMs = if (first != null && stamps.size == 1) minOf(s, first) else s,
                    endMs = end?.let { (it - offset).coerceAtLeast(0) } ?: 0,
                    text = mainText,
                    words = mainWords,
                    backing = backWords,
                    backingText = backText.ifBlank { null },
                    voice = voice,
                    aside = aside,
                )
            }
        }
        if (ends.isEmpty()) return lines
        // An empty stamped line closes the line before it.
        val sortedEnds = ends.sorted()
        return lines.map { line ->
            if (line.endMs > 0) return@map line
            val close = sortedEnds.firstOrNull { it > line.startMs } ?: return@map line
            val nextStart = lines.filter { it.startMs > line.startMs }.minOfOrNull { it.startMs }
            if (nextStart == null || close < nextStart) line.copy(endMs = close) else line
        }
    }

    private fun mlxLyricsSection(text: String): List<String> {
        var section = "lyrics"
        val out = mutableListOf<String>()
        for (raw in text.lines()) {
            val name = SECTION.matchEntire(raw.trim())?.groupValues?.get(1)?.lowercase()
            if (name != null && name in MLX_SECTIONS) {
                section = name
                continue
            }
            if (section == "lyrics") out += raw
        }
        return out
    }

    /**
     * The words of one line with their tags: text between two tags is a word sung from the first
     * to the second, so both `<a>word <b>word<c>` (MLX, ends given) and `<a>word <b>word` (enhanced
     * LRC, each word to the next) read the same. Text before the first tag has no time of its own.
     */
    private fun words(content: String, offset: Long, lineStartMs: Long?, mlx: Boolean): Pair<String, List<LyricWord>> {
        val marks = WORD_TAG.findAll(content).toList()
        if (marks.isEmpty()) return content.trim().replace(SPACES, " ") to emptyList()
        val lead = content.substring(0, marks.first().range.first)
        val text = StringBuilder(lead)
        val out = mutableListOf<LyricWord>()
        // Words before the first tag are sung from the line's start to that tag.
        if (lead.isNotBlank()) {
            val firstTag = clock(marks.first().groupValues[1]) - offset
            val from = (lineStartMs ?: firstTag).coerceAtMost(firstTag)
            val pieces = lead.trim().split(SPACES)
            val step = (firstTag - from).coerceAtLeast(0) / pieces.size
            pieces.forEachIndexed { k, w -> out += LyricWord(w, from + step * k, from + step * (k + 1), true) }
        }
        for ((i, m) in marks.withIndex()) {
            val from = m.range.last + 1
            val to = if (i + 1 < marks.size) marks[i + 1].range.first else content.length
            val piece = content.substring(from, to)
            text.append(piece)
            if (piece.isBlank()) {
                // A gap between an end and the next start: the space belongs to the word before.
                if (piece.isNotEmpty() && out.isNotEmpty()) out[out.lastIndex] = out.last().copy(space = true)
                continue
            }
            // In MLX every word is closed by its own tag: text after the last one is punctuation.
            if (mlx && i == marks.lastIndex && out.isNotEmpty()) {
                out[out.lastIndex] = out.last().let { it.copy(text = it.text + piece.trim()) }
                continue
            }
            val start = clock(m.groupValues[1]) - offset
            // The last word of an enhanced LRC line has no closing tag: it ends with its line.
            val end = if (i + 1 < marks.size) clock(marks[i + 1].groupValues[1]) - offset else OPEN_END
            val spaced = piece.endsWith(' ') || piece.endsWith('\t')
            val leading = piece.startsWith(' ')
            if (leading && out.isNotEmpty()) out[out.lastIndex] = out.last().copy(space = true)
            out += LyricWord(piece.trim(), start.coerceAtLeast(0), if (end == OPEN_END) OPEN_END else end.coerceAtLeast(start), spaced)
        }
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(space = false)
        return text.toString().trim().replace(SPACES, " ") to out
    }

    /** A line that is all backing vocals goes under the line it is sung with, when there is one. */
    fun attachBacking(lines: List<LyricLine>): List<LyricLine> {
        if (lines.none { it.aside }) return lines
        val out = mutableListOf<LyricLine>()
        for (line in lines) {
            val host = out.lastOrNull()
            if (line.aside && host != null && !host.aside && host.backing.isEmpty() && host.backingText == null &&
                line.startMs >= host.startMs && (host.endMs == 0L || line.startMs < host.endMs + 400)
            ) {
                out[out.lastIndex] = host.copy(backing = line.words, backingText = line.text)
            } else {
                out += line
            }
        }
        return out
    }

    /** `mm:ss`, `mm:ss.x`, `mm:ss.xx`, `mm:ss.xxx`, `mm:ss:xx`, or `hh:mm:ss.xxx`, in ms. */
    internal fun clock(stamp: String): Long {
        val parts = stamp.trim().split(':')
        return when (parts.size) {
            3 -> {
                // mm:ss:xx (the colon form of hundredths) or hh:mm:ss.xxx
                if (parts[2].contains('.') || parts[2].length > 2) {
                    parts[0].toLong() * 3_600_000 + parts[1].toLong() * 60_000 + seconds(parts[2])
                } else {
                    parts[0].toLong() * 60_000 + parts[1].toLong() * 1000 + fraction(parts[2])
                }
            }
            2 -> parts[0].toLong() * 60_000 + seconds(parts[1])
            else -> seconds(parts[0])
        }
    }

    private fun seconds(s: String): Long {
        val dot = s.indexOf('.')
        if (dot < 0) return s.toLong() * 1000
        return s.substring(0, dot).ifEmpty { "0" }.toLong() * 1000 + fraction(s.substring(dot + 1))
    }

    private fun fraction(f: String): Long = when (f.length) {
        0 -> 0
        1 -> f.toLong() * 100
        2 -> f.toLong() * 10
        else -> f.take(3).toLong()
    }

    private const val MAX_CHARS = 8 * 1024 * 1024
    private const val LAST_LINE_MS = 6_000L
    /** A word whose end is not given lasts until its line ends, but no longer than this. */
    private const val OPEN_WORD_MS = 1_400L
    private const val OPEN_END = -1L
    private val KNOWN = setOf("ttml", "mlx", "lrc", "plain")
    private val MLX_SECTIONS = setOf(
        "meta", "song", "agents", "agentcolors", "lyrics", "romanlyrics", "roman_lyrics", "translatedlyrics", "translated_lyrics",
        "colors", "songparts", "song_parts",
    )
    private const val STAMP = """\d+:\d{2}(?:[.:]\d{1,3})?"""
    private val LINE_STAMP = Regex("""\[$STAMP]""")
    private val HEADER = Regex("""\[($STAMP)(?:~($STAMP))?(?:\|([^\]|]+))?]""")
    private val WORD_TAG = Regex("""<($STAMP)>""")
    private val BACKING = Regex("""\s*\[bg:(.*)]\s*$""")
    private val SECTION = Regex("""^\[([a-zA-Z_]+)]$""")
    private val META = Regex("""^\[(?:ar|al|ti|au|by|re|ve|length|offset|tool|#)[: ].*]$""", RegexOption.IGNORE_CASE)
    private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)
    // Every brace escaped: Android's ICU regex refuses a bare "}" (the JVM accepts it), and the
    // whole object failed to load there, taking the app down with the first lyrics opened.
    private val LEGACY_VOICE = Regex("""\{agent:([^\}]+)\}""")
    private val PREFIX_VOICE = Regex("""^(v\d+):\s*""")
    private val SPACES = Regex("""\s+""")
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|\+|/|;|\bx\b|\band\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\bvs\.?)\s*""", RegexOption.IGNORE_CASE)
    private val NON_LETTERS = Regex("""[^\p{L}\p{N}]+""")
}

/** MLX version 2: the JSON Lyprio answers in. Only what Valen shows is read. */
private object MlxJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(text: String): List<LyricLine>? {
        val doc = runCatching { json.decodeFromString<Doc>(text) }.getOrNull() ?: return null
        if (doc.lines.isEmpty() || doc.lines.size > 5_000) return null
        return doc.lines.map { line ->
            fun List<Word>.read() = map { LyricWord(it.text.trimEnd(), it.startMs, it.endMs.coerceAtLeast(it.startMs), it.text.endsWith(' ')) }
                .filter { it.text.isNotEmpty() }
                .let { ws -> if (ws.isEmpty()) ws else ws.dropLast(1) + ws.last().copy(space = false) }
            val words = line.words.read()
            val backing = line.backgroundWords.read()
            val text = line.text.ifBlank { words.joinToString("") { it.text + if (it.space) " " else "" } }
            LyricLine(
                startMs = line.startMs ?: 0,
                endMs = line.endMs?.takeIf { line.startMs != null } ?: 0,
                text = if (line.background && text.isBlank()) line.backgroundText.orEmpty() else text.trim(),
                words = words,
                backing = backing,
                backingText = line.backgroundText?.takeIf { it.isNotBlank() } ?: backing.takeIf { it.isNotEmpty() }?.joinToString("") { it.text + if (it.space) " " else "" }?.trim(),
                voice = line.agent,
                aside = line.background,
            )
        }
    }

    @Serializable
    private data class Doc(val version: Int = 2, val lines: List<Line> = emptyList())

    @Serializable
    private data class Line(
        val startMs: Long? = null,
        val endMs: Long? = null,
        val text: String = "",
        val background: Boolean = false,
        val agent: String? = null,
        val words: List<Word> = emptyList(),
        val backgroundWords: List<Word> = emptyList(),
        val backgroundText: String? = null,
    )

    @Serializable
    private data class Word(val text: String, val startMs: Long, val endMs: Long)
}

/** TTML: `<p begin end>` lines of `<span begin end>` words; `role="x-bg"` spans are backing vocals. */
private object Ttml {
    fun read(text: String): List<LyricLine>? {
        val root = runCatching { Xml.parse(text) }.getOrNull() ?: return null
        val body = root.find("body") ?: return null
        val lines = mutableListOf<LyricLine>()
        fun walk(el: Xml.Element, voice: String?) {
            val here = el.attr("agent") ?: voice
            if (el.name == "p") {
                paragraph(el, here)?.let { lines += it }
                return
            }
            el.elements().forEach { walk(it, here) }
        }
        walk(body, null)
        if (lines.isEmpty()) {
            // Untimed TTML: the paragraphs' text, as plain lines.
            val plain = mutableListOf<LyricLine>()
            fun collect(el: Xml.Element) {
                if (el.name == "p") el.text().trim().takeIf { it.isNotEmpty() }?.let { plain += LyricLine(0, 0, it) } else el.elements().forEach(::collect)
            }
            collect(body)
            return plain.ifEmpty { null }
        }
        return lines
    }

    private fun paragraph(p: Xml.Element, voice: String?): LyricLine? {
        val begin = p.attr("begin")?.let(::time)
        val end = p.attr("end")?.let(::time)
        val words = mutableListOf<LyricWord>()
        val backing = mutableListOf<LyricWord>()
        var backingText: String? = null
        val aside = p.attr("role") == "x-bg"
        val kids = p.children
        for ((i, node) in kids.withIndex()) {
            val span = node as? Xml.Element ?: continue
            if (span.name != "span") continue
            when (span.attr("role")) {
                "x-translation", "x-roman" -> Unit
                "x-bg" -> if (aside) spanWords(span, kids.getOrNull(i + 1), words) else {
                    val inner = span.elements().filter { it.name == "span" && it.attr("role") !in IGNORED }
                    if (inner.isEmpty()) {
                        backingText = span.text().trim().trim('(', ')').ifBlank { null }
                    } else {
                        for ((j, w) in span.children.withIndex()) {
                            val ws = w as? Xml.Element ?: continue
                            if (ws.name == "span" && ws.attr("role") !in IGNORED) spanWords(ws, span.children.getOrNull(j + 1), backing)
                        }
                        backingText = backing.joinToString("") { it.text + if (it.space) " " else "" }.trim().trim('(', ')')
                    }
                }
                else -> spanWords(span, kids.getOrNull(i + 1), words)
            }
        }
        if (words.isNotEmpty()) words[words.lastIndex] = words.last().copy(space = false)
        if (backing.isNotEmpty()) backing[backing.lastIndex] = backing.last().copy(space = false)
        val text = if (words.isNotEmpty()) words.joinToString("") { it.text + if (it.space) " " else "" }.trim() else directText(p).trim()
        val start = begin ?: words.firstOrNull()?.startMs ?: return null
        if (text.isEmpty() && backingText == null) return null
        return LyricLine(start, end ?: 0, text, words, backing, backingText, voice, aside)
    }

    /** One timed span as a word; the text right after it says whether a space follows. */
    private fun spanWords(span: Xml.Element, next: Xml.Node?, into: MutableList<LyricWord>) {
        val b = span.attr("begin")?.let(::time) ?: return
        val e = span.attr("end")?.let(::time) ?: return
        val raw = span.text()
        if (raw.isBlank()) return
        val after = (next as? Xml.Text)?.value.orEmpty()
        val spaced = raw.endsWith(' ') || (after.isNotEmpty() && after.first().isWhitespace() && !(after.startsWith("\n") && after.isBlank()))
        into += LyricWord(raw.trim(), b, e.coerceAtLeast(b), spaced)
    }

    private fun directText(el: Xml.Element): String = buildString {
        for (node in el.children) when (node) {
            is Xml.Text -> append(node.value)
            is Xml.Element -> if (node.attr("role") !in IGNORED && node.attr("role") != "x-bg") append(directText(node))
        }
    }.replace(Regex("\\s+"), " ")

    /** `12.345s`, `1500ms`, `mm:ss.fff`, `hh:mm:ss.fff`, or bare seconds. */
    fun time(value: String): Long? {
        val v = value.trim()
        return runCatching {
            when {
                v.endsWith("ms") -> v.removeSuffix("ms").toDouble().toLong()
                v.endsWith("s") && !v.contains(':') -> (v.removeSuffix("s").toDouble() * 1000).toLong()
                v.contains(':') -> LyricsFormats.clock(v)
                else -> (v.toDouble() * 1000).toLong()
            }
        }.getOrNull()
    }

    private val IGNORED = setOf("x-translation", "x-roman")
}

/**
 * A small, forgiving XML reader: elements, attributes and text, entities decoded, comments,
 * declarations and CDATA handled. Names are kept without their namespace prefix.
 */
internal object Xml {
    sealed interface Node
    class Text(val value: String) : Node
    class Element(val name: String, val attrs: Map<String, String>, val children: MutableList<Node> = mutableListOf()) : Node {
        fun attr(name: String): String? = attrs[name]
        fun elements(): List<Element> = children.filterIsInstance<Element>()
        fun find(name: String): Element? {
            if (this.name == name) return this
            for (child in elements()) child.find(name)?.let { return it }
            return null
        }
        fun text(): String = buildString { children.forEach { if (it is Text) append(it.value) else if (it is Element) append(it.text()) } }
    }

    fun parse(src: String): Element {
        var i = 0
        val root = Element("#document", emptyMap())
        val stack = ArrayDeque<Element>().apply { addLast(root) }
        var nodes = 0
        while (i < src.length) {
            if (++nodes > 400_000) error("too many nodes")
            val lt = src.indexOf('<', i)
            if (lt < 0) {
                stack.last().children += Text(decode(src.substring(i)))
                break
            }
            if (lt > i) stack.last().children += Text(decode(src.substring(i, lt)))
            when {
                src.startsWith("<!--", lt) -> i = src.indexOf("-->", lt).let { if (it < 0) src.length else it + 3 }
                src.startsWith("<![CDATA[", lt) -> {
                    val end = src.indexOf("]]>", lt).let { if (it < 0) src.length else it }
                    stack.last().children += Text(src.substring(lt + 9, end))
                    i = (end + 3).coerceAtMost(src.length)
                }
                src.startsWith("<?", lt) || src.startsWith("<!", lt) -> i = src.indexOf('>', lt).let { if (it < 0) src.length else it + 1 }
                src.startsWith("</", lt) -> {
                    val gt = src.indexOf('>', lt).let { if (it < 0) src.length else it }
                    val name = local(src.substring(lt + 2, gt).trim())
                    // Close up to the matching element; a stray close tag is ignored.
                    if (stack.any { it.name == name }) while (stack.size > 1) if (stack.removeLast().name == name) break
                    i = gt + 1
                }
                else -> {
                    val gt = tagEnd(src, lt)
                    val inner = src.substring(lt + 1, gt)
                    val selfClosing = inner.endsWith('/')
                    val body = if (selfClosing) inner.dropLast(1) else inner
                    val nameEnd = body.indexOfFirst { it.isWhitespace() }.let { if (it < 0) body.length else it }
                    val el = Element(local(body.substring(0, nameEnd)), attributes(body.substring(nameEnd)))
                    stack.last().children += el
                    if (!selfClosing) stack.addLast(el)
                    i = gt + 1
                }
            }
        }
        return root.elements().firstOrNull() ?: root
    }

    /** The `>` that ends a tag, skipping any inside quoted attribute values. */
    private fun tagEnd(src: String, from: Int): Int {
        var quote: Char? = null
        for (j in from + 1 until src.length) {
            val c = src[j]
            if (quote != null) { if (c == quote) quote = null } else if (c == '"' || c == '\'') quote = c else if (c == '>') return j
        }
        return src.length
    }

    private fun attributes(s: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ATTR.findAll(s).forEach { m ->
            val value = decode(m.groupValues[2].ifEmpty { m.groupValues[3] })
            val full = m.groupValues[1]
            out[local(full)] = value
            out[full] = value
        }
        return out
    }

    private fun local(name: String) = name.substringAfter(':')

    private fun decode(s: String): String {
        if (!s.contains('&')) return s
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e == "amp" -> "&"
                e == "lt" -> "<"
                e == "gt" -> ">"
                e == "quot" -> "\""
                e == "apos" -> "'"
                e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> m.value
            }
        }
    }

    private val ATTR = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val ENTITY = Regex("""&(#?[xX]?[0-9a-zA-Z]+);""")
}
