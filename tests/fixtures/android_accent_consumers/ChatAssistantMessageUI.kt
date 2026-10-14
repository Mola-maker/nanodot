// Exact consumer excerpts from reviewed ac21ebeea177c948266317732ac9000d1d8ac974.
// ChatAssistantMessageUI.kt:870-872
    }
    val thinkingBlue = Color(0xFF2D6396)
    val charCount = block.content.length
// ChatAssistantMessageUI.kt:892-898
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(thinkingBlue.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
// ChatAssistantMessageUI.kt:935-968
            Text(
                text = "Deep Thinking",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = thinkingBlue,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (charCount > 0) {
                Text(
                    text = charLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = thinkingBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (overHardCap) {
                // [T-thinking-render-perf-android] No expand/collapse chevron —
                // the content is too large for the inline Compose scroller.
                // Offer the native full-content viewer instead.
                Text(
                    text = stringResource(R.string.thinking_view_full),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = thinkingBlue,
                )
            } else {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = thinkingBlue.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
// ChatAssistantMessageUI.kt:1096-1103
                ) {
                    Text(
                        text = "Deep Thinking",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2D6396),
                    )
                    Spacer(modifier = Modifier.weight(1f))
