package io.github.nanomuse.dot.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nanomuse.dot.ui.DotTokens
import io.github.nanomuse.dot.ui.dotGlassPlateColor

/** The canonical value is unchanged when a short host requires horizontal single-line editing. */
@Composable
fun DotDraftField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, compact: Boolean = false) {
    val singleLine = LocalDotForegroundTight.current || compact
    val shape = RoundedCornerShape(18.dp)
    val style = LocalTextStyle.current.copy(fontSize = DotTokens.Type.Body,
        lineHeight = 20.sp, color = Color.White)
    BasicTextField(value, onValueChange,
        modifier.heightIn(min = DotTokens.TouchTarget).semantics { contentDescription = label }
            .clip(shape).background(dotGlassPlateColor())
            .border(.75.dp, Color(0xFFB9DCFF).copy(alpha = .26f), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        textStyle = style, cursorBrush = SolidColor(Color.White),
        singleLine = singleLine, maxLines = if (singleLine) 1 else 2,
        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(label, style = style, maxLines = 1)
            inner()
        } })
}
