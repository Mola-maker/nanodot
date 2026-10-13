package io.github.nanomuse.dot.care

import android.graphics.Paint
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nanomuse.dot.screen.RectPx
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native ICU and StaticLayout; no model or application fixture. */
@RunWith(AndroidJUnit4::class)
class CareInstructionTextTest {
    @Test fun revealNeverSplitsEmojiCombiningMarksFlagsOrCjk() {
        val clusters = listOf("A", "e\u0301", "👩🏽‍💻", "🇯🇵", "界")
        val text = clusters.joinToString("")
        val expected = clusters.runningFold(0) { n, s -> n+s.length }.drop(1).toIntArray()
        assertArrayEquals(expected,careGraphemeEnds(text,Locale.JAPANESE))
    }
    @Test fun arabicUsesActualRtlLayoutAndLargerFontsSuppressUnsafeCue() {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize=36f }
        val text = "اضغط على زر البحث ثم اكتب ما تحتاجه"
        val normal = careInstructionLayout(text,paint,300,true)
        assertEquals(-1,normal.getParagraphDirection(0))
        assertEquals(text.length,normal.getLineEnd(normal.lineCount-1))
        paint.textSize=72f
        val large = careInstructionLayout(text,paint,300,true)
        assertTrue(large.height>normal.height)
        val host=RectPx(0f,0f,420f,600f)
        val target=RectPx(0f,180f,420f,380f)
        val card=RectPx(0f,400f,420f,600f)
        assertNull(careInstructionBounds(target,card,host,324f,large.height+24f,12f,true))
    }
}
