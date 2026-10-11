package io.github.polarfox0317.sikboonge.ui.state

import androidx.compose.runtime.Immutable
import io.github.polarfox0317.sikboonge.AudioResult
import io.github.polarfox0317.sikboonge.BotVideo
import io.github.polarfox0317.sikboonge.TagPolicy
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

enum class ListLayout { List, Grid }
enum class SortKey(val label: String) { Name("이름"), Date("날짜") }
enum class DateRange(val label: String) {
    All("전체 기간"), Week("최근 7일"), Month("최근 30일")
}

@Immutable
data class ListQuery(
    val text: String = "",
    val sortKey: SortKey = SortKey.Date,
    val ascending: Boolean = false,
    val range: DateRange = DateRange.All,
    val tags: Set<String> = emptySet(),
) {
    val active: Boolean get() = text.isNotBlank() || range != DateRange.All || tags.isNotEmpty()
}

fun filterLibrary(
    items: List<AudioResult>,
    query: ListQuery,
    nowMs: Long = System.currentTimeMillis(),
): List<AudioResult> {
    val matches = items.filter { matchesTextTerms(it.name, it.tags, query.text) && matchesTagFilter(it.tags, query.tags) && matchesDate(it.date, query.range, nowMs) }
    return sortItems(matches, query.sortKey, query.ascending, AudioResult::name, AudioResult::date)
}

fun filterBot(
    items: List<BotVideo>,
    query: ListQuery,
    nowMs: Long = System.currentTimeMillis(),
): List<BotVideo> {
    // AudioResult timestamps are milliseconds; TDLib Message.date (BotVideo.date) is seconds.
    val matches = items.filter { matchesTextTerms(it.displayName, it.tags, query.text) && matchesTagFilter(it.tags, query.tags) && matchesDate(it.date * 1_000L, query.range, nowMs) }
    return sortItems(matches, query.sortKey, query.ascending, BotVideo::displayName) { it.date * 1_000L }
}

private fun normalizedName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

private fun searchTerms(value: String): List<String> =
    normalizedName(value).trim().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)

internal fun matchesTextTerms(title: String, tags: List<String>, text: String): Boolean {
    val normalizedTitle = normalizedName(title)
    val tagKeys = tags.map(TagPolicy::key)
    return searchTerms(text).all { term ->
        if (term.startsWith('#') && term.drop(1).isNotEmpty()) {
            val tagTerm = term.drop(1)
            tagKeys.any { it.contains(tagTerm) }
        } else {
            normalizedTitle.contains(term) || tagKeys.any { it.contains(term) }
        }
    }
}

internal fun matchesTagFilter(tags: List<String>, selected: Set<String>): Boolean {
    if (selected.isEmpty()) return true
    val itemKeys = tags.mapTo(hashSetOf(), TagPolicy::key)
    return selected.all { TagPolicy.key(it) in itemKeys }
}

internal fun ListQuery.withAvailableTags(tags: List<List<String>>): ListQuery {
    val available = tags.flatten().mapTo(hashSetOf(), TagPolicy::key)
    return copy(tags = this.tags.mapTo(linkedSetOf(), TagPolicy::key).intersect(available))
}

internal fun applyBotTagOverrides(items: List<BotVideo>, overrides: Map<Long, List<String>>): List<BotVideo> =
    items.map { video -> overrides[video.messageId]?.let { video.copy(tags = it) } ?: video }

internal fun tagVocabulary(vararg sources: List<List<String>>): List<String> {
    val displayByKey = linkedMapOf<String, String>()
    val counts = mutableMapOf<String, Int>()
    sources.forEach { source -> source.forEach { tags ->
        TagPolicy.normalizeTags(tags).forEach { tag ->
            val key = TagPolicy.key(tag)
            displayByKey.putIfAbsent(key, tag)
            counts[key] = (counts[key] ?: 0) + 1
        }
    } }
    val collator = Collator.getInstance(Locale.KOREAN)
    return displayByKey.keys.sortedWith { first, second ->
        val byCount = counts.getValue(second).compareTo(counts.getValue(first))
        if (byCount != 0) byCount else collator.compare(displayByKey.getValue(first), displayByKey.getValue(second))
    }.map(displayByKey::getValue)
}

private fun matchesDate(dateMs: Long, range: DateRange, nowMs: Long): Boolean = when (range) {
    DateRange.All -> true
    DateRange.Week -> dateMs in (nowMs - 7L * 86_400_000L)..nowMs
    DateRange.Month -> dateMs in (nowMs - 30L * 86_400_000L)..nowMs
}

private fun <T> sortItems(
    items: List<T>, sortKey: SortKey, ascending: Boolean, name: (T) -> String, date: (T) -> Long,
): List<T> = when (sortKey) {
    SortKey.Date -> if (ascending) items.sortedBy(date) else items.sortedByDescending(date)
    SortKey.Name -> {
        val collator = Collator.getInstance(Locale.KOREAN)
        val comparator = Comparator<T> { first, second ->
            collator.compare(Normalizer.normalize(name(first), Normalizer.Form.NFC), Normalizer.normalize(name(second), Normalizer.Form.NFC))
        }
        items.sortedWith(if (ascending) comparator else comparator.reversed())
    }
}
