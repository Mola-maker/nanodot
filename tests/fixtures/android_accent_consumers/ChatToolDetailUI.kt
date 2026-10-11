// Exact consumer excerpts from reviewed ac21ebeea177c948266317732ac9000d1d8ac974.
// ChatToolDetailUI.kt:560-571
                                            // TerminalCanvasView.addURLLinks → MinisOpenURLBroker).
                                            val urlClick = LocalMarkdownUrlClickHandler.current
                                            val linkified = remember(block.content, urlClick) {
                                                if (urlClick != null) {
                                                    com.openminis.app.ui.util.linkifyUrls(
                                                        text = block.content,
                                                        onClick = urlClick,
                                                        linkColor = Color(0xFF7CC4FF), // light blue on dark terminal bg
                                                    )
                                                } else androidx.compose.ui.text.AnnotatedString(block.content)
                                            }
                                            Text(
// ChatToolDetailUI.kt:909-918
                                        Text(
                                            text = action,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            modifier = Modifier
                                                .background(Color(0xFF2D6396), CircleShape)
                                                .padding(horizontal = 10.dp, vertical = 2.dp),
                                        )
                                    }
// ChatToolDetailUI.kt:1474-1495
private fun LazyRevealToolText(
    bodyText: String,
    color: Color,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    linkify: Boolean = false,
) {
    val chunks = remember(bodyText) { chunkToolOutput(bodyText) }
    // Reset the reveal window whenever the underlying text changes (e.g. the
    // user pages to a different tool block, which swaps bodyText).
    var revealed by remember(bodyText) { mutableStateOf(initialRevealChunks(chunks)) }
    val total = chunks.size
    val shownText = remember(bodyText, revealed) {
        chunks.take(revealed.coerceIn(1, total.coerceAtLeast(1))).joinToString("\n")
    }
    val urlClick = LocalMarkdownUrlClickHandler.current
    val displayed = remember(shownText, urlClick, linkify) {
        if (linkify && urlClick != null) {
            com.openminis.app.ui.util.linkifyUrls(text = shownText, onClick = urlClick)
        } else androidx.compose.ui.text.AnnotatedString(shownText)
    }
