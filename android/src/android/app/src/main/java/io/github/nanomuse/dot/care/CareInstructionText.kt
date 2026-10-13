package io.github.nanomuse.dot.care

import android.icu.text.BreakIterator
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.util.Locale

/** ICU character boundaries keep emoji, combining marks and surrogate pairs together. */
internal fun careGraphemeEnds(text: String, locale: Locale): IntArray {
    val iterator = BreakIterator.getCharacterInstance(locale).apply { setText(text) }
    val ends = mutableListOf<Int>()
    var end = iterator.next()
    while (end != BreakIterator.DONE) { ends += end; end = iterator.next() }
    return ends.toIntArray()
}

internal fun careInstructionLayout(text: String, paint: TextPaint, width: Int, rtl: Boolean): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setTextDirection(if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setIncludePad(true).setLineSpacing(0f, 1.12f).build()
