// Exact EntryRow icon excerpt from 2e50b994872c06375aa0fb13e8e7fb521bbc9a46; source lines 256-284.
@Composable
private fun EntryRow(
    entry: RcloneChunkedUpload.RemoteEntry,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).background(
                    if (entry.isDirectory) Color(0xFF8E8E93) else androidx.compose.material3.MaterialTheme.colorScheme.primary, // nanoMuse: active-theme foreground accent.
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Inventory2,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
