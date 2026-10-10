package io.github.polarfox0317.sikboonge.update

/** A small presentation model that keeps release-note paragraphs and list rows distinct. */
internal sealed interface ReleaseNoteBlock {
    data class Heading(val text: String) : ReleaseNoteBlock
    data class Paragraph(val text: String) : ReleaseNoteBlock
    data class Item(val text: String, val depth: Int = 0, val marker: String = "•") : ReleaseNoteBlock
}

internal data class ReleaseNotesContent(
    val blocks: List<ReleaseNoteBlock>,
    val releaseUrl: String?,
)

internal fun releasePageUrl(tagName: String): String =
    "https://github.com/Polarfox0317/sikboonge/releases/tag/${java.net.URLEncoder.encode(tagName, Charsets.UTF_8.name()).replace("+", "%20")}"

/** Select one locale section, or the first populated section when the requested locale is absent. */
internal fun parseReleaseNotes(
    body: String,
    preferredLanguage: String?,
    releaseTitle: String,
    version: String,
    releaseUrl: String?,
): ReleaseNotesContent {
    val lines = body.lines()
    val sections = mutableListOf<Pair<String, List<String>>>()
    var currentLanguage: String? = null
    var currentLines = mutableListOf<String>()
    var sawLanguageHeading = false

    fun save() {
        currentLanguage?.let { sections += it to currentLines.toList() }
    }
    lines.forEach { line ->
        val heading = languageHeading(line)
        if (heading != null) {
            save()
            currentLanguage = heading
            currentLines = mutableListOf()
            sawLanguageHeading = true
        } else currentLines += line
    }
    save()

    val ignoredTitles = setOf(releaseTitle.trim().lowercase(), version.trim().lowercase(), "v${version.trim()}".lowercase())
    val selectedBlocks = if (!sawLanguageHeading) {
        parseBlocks(lines, ignoredTitles, version)
    } else {
        val requested = preferredLanguage?.lowercase()?.let { if (it.startsWith("ko")) "ko" else if (it.startsWith("en")) "en" else it }
        val orderedSections = buildList {
            sections.firstOrNull { it.first == requested }?.let(::add)
            sections.filterNot { it.first == requested }.forEach(::add)
        }
        orderedSections.asSequence()
            .map { parseBlocks(it.second, ignoredTitles, version) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }
    return ReleaseNotesContent(selectedBlocks, releaseUrl)
}

private fun languageHeading(line: String): String? {
    val text = line.trim().replace(Regex("^#{1,6}\\s*"), "").trim().trim('*', '_', ':').trim().lowercase()
    return when (text) {
        "한국어", "korean", "ko", "ko-kr" -> "ko"
        "english", "en", "en-us", "en-gb" -> "en"
        else -> null
    }
}

private val listPattern = Regex("^(\\s*)([-*+]\\s+|\\d+[.)]\\s+)(.+)$")
private val headingPattern = Regex("^#{1,6}\\s+(.+)$")

private fun parseBlocks(lines: List<String>, ignoredTitles: Set<String>, version: String): List<ReleaseNoteBlock> {
    val result = mutableListOf<ReleaseNoteBlock>()
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            val text = paragraph.joinToString(" ").trim()
            if (text.isNotEmpty() && !isDuplicateHeader(text, ignoredTitles, version)) {
                result += ReleaseNoteBlock.Paragraph(inlineMarkdown(text))
            }
            paragraph.clear()
        }
    }
    lines.forEach { raw ->
        val line = raw.trimEnd()
        val trimmed = line.trim()
        if (trimmed.isEmpty()) { flushParagraph(); return@forEach }
        val list = listPattern.matchEntire(line)
        if (list != null) {
            flushParagraph()
            val marker = list.groupValues[2]
            val depth = list.groupValues[1].count { it == ' ' || it == '\t' } / 2
            val displayMarker = if (marker.first().isDigit()) marker.trim() else "•"
            result += ReleaseNoteBlock.Item(inlineMarkdown(list.groupValues[3].trim()), depth, displayMarker)
        } else {
            val heading = headingPattern.matchEntire(trimmed)
            if (heading != null) {
                flushParagraph()
                val title = inlineMarkdown(heading.groupValues[1])
                if (!isDuplicateHeader(title, ignoredTitles, version)) result += ReleaseNoteBlock.Heading(title)
            } else paragraph += trimmed
        }
    }
    flushParagraph()
    return result
}

private fun inlineMarkdown(value: String): String = value
    .let { value ->
        val code = mutableListOf<String>()
        val protected = Regex("`([^`]+)`").replace(value) { match ->
            code += match.groupValues[1]
            "\u0000${code.lastIndex}\u0000"
        }
        protected
            .replace(Regex("!?\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^]]+)]\\[[^]]*]"), "$1")
            .replace(Regex("\\*\\*(.+?)\\*\\*|__(.+?)__"), "$1$2")
            .replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)|(?<![\\p{L}\\p{N}])_([^_]+)_(?![\\p{L}\\p{N}])"), "$1$2")
            .replace(Regex("\\u0000(\\d+)\\u0000")) { match -> code[match.groupValues[1].toInt()] }
            .trim()
    }

private fun isDuplicateHeader(text: String, ignored: Set<String>, version: String): Boolean {
    val normalized = text.trim().trimStart('#').trim().lowercase()
    val versionHeader = setOf(version.lowercase(), "v${version.lowercase()}", "sikboonge ${version.lowercase()}", "sikboonge v${version.lowercase()}")
    return normalized in ignored || normalized in versionHeader
}
