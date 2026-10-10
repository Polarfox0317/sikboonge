package io.github.polarfox0317.sikboonge.ui.state

import androidx.compose.runtime.Immutable
import io.github.polarfox0317.sikboonge.AudioResult
import io.github.polarfox0317.sikboonge.BotVideo
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
) {
    val active: Boolean get() = text.isNotBlank() || range != DateRange.All
}

fun filterLibrary(
    items: List<AudioResult>,
    query: ListQuery,
    nowMs: Long = System.currentTimeMillis(),
): List<AudioResult> {
    val terms = searchTerms(query.text)
    val matches = items.filter { matchesName(it.name, terms) && matchesDate(it.date, query.range, nowMs) }
    return sortItems(matches, query.sortKey, query.ascending, AudioResult::name, AudioResult::date)
}

fun filterBot(
    items: List<BotVideo>,
    query: ListQuery,
    nowMs: Long = System.currentTimeMillis(),
): List<BotVideo> {
    val terms = searchTerms(query.text)
    // AudioResult timestamps are milliseconds; TDLib Message.date (BotVideo.date) is seconds.
    val matches = items.filter { matchesName(it.displayName, terms) && matchesDate(it.date * 1_000L, query.range, nowMs) }
    return sortItems(matches, query.sortKey, query.ascending, BotVideo::displayName) { it.date * 1_000L }
}

private fun normalizedName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

private fun searchTerms(value: String): List<String> =
    normalizedName(value).trim().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)

private fun matchesName(name: String, terms: List<String>): Boolean {
    val normalized = normalizedName(name)
    return terms.all(normalized::contains)
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
