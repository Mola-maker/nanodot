// Exact consumer excerpts from reviewed ac21ebeea177c948266317732ac9000d1d8ac974.
// ChatModelPickerSheet.kt:724-736
                                        Text(
                                            stringResource(R.string.model_picker_default_badge),
                                            fontSize = 9.sp,
                                            lineHeight = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF2D6396),
                                            modifier = Modifier
                                                .background(
                                                    Color(0xFF2D6396).copy(alpha = 0.1f),
                                                    RoundedCornerShape(50),
                                                )
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
// ChatModelPickerSheet.kt:1105-1121
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = Color(0xFF2D6396),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                pluralStringResource(
                                                    R.plurals.model_picker_show_models,
                                                    entries.size,
                                                    entries.size,
                                                ),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color(0xFF2D6396),
                                            )
                                        }
