package com.foamplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Minimal GitHub-flavored markdown renderer for release notes: headings,
 * bullet / numbered lists, fenced code blocks, blockquotes, horizontal rules,
 * block-level images and inline bold / italic / strikethrough / inline code /
 * links. Unrecognized syntax renders as plain text, so unusual release bodies
 * never break layout.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val colors = FoamTheme.colors
    val typography = FoamTheme.typography
    val accent = FoamTheme.accentColor
    val styles = remember(colors, accent) {
        InlineStyles(
            bold = SpanStyle(fontWeight = FontWeight.Bold),
            italic = SpanStyle(fontStyle = FontStyle.Italic),
            strike = SpanStyle(textDecoration = TextDecoration.LineThrough),
            code = SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = colors.surfaceVariant,
                color = colors.textPrimary
            ),
            link = SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
        )
    }
    val blocks = remember(markdown) { parseMarkdown(markdown) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
    ) {
        blocks.forEach { block ->
            when (block) {
                is Block.Heading -> BasicText(
                    text = annotated(block.text, styles),
                    style = when {
                        block.level <= 2 -> typography.titleMedium
                        block.level == 3 -> typography.body.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        else -> typography.body.copy(fontWeight = FontWeight.SemiBold)
                    }
                )

                is Block.Paragraph -> BasicText(
                    text = annotated(block.text, styles),
                    style = typography.body
                )

    is Block.ListBlock -> Column(
                    verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingTiny)
                ) {
                    block.items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = FoamDimens.spacingStandard * item.indent)
                        ) {
                            BasicText(
                                text = item.marker,
                                style = typography.body.copy(color = colors.textSecondary)
                            )
                            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                            BasicText(
                                text = annotated(item.text, styles),
                                style = typography.body,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                is Block.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(accent)
                    )
                    Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                    BasicText(
                        text = annotated(block.text, styles),
                        style = typography.body,
                        modifier = Modifier.weight(1f)
                    )
                }

                is Block.Code -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.itemRounding))
                        .background(colors.surfaceVariant)
                        .padding(FoamDimens.paddingSmall)
                ) {
                    BasicText(text = block.code.trimEnd('\n'), style = typography.monospace)
                }

                Block.Rule -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.borderOverlay)
                )

                is Block.Image -> AsyncImage(
                    model = block.url,
                    contentDescription = block.alt.ifBlank { null },
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }
}

/** Resolves [text]'s inline markdown (bold, italic, code, links...) to styled spans. */
private fun annotated(text: String, styles: InlineStyles): AnnotatedString =
    buildAnnotatedString { appendInline(this, text, base = null, styles) }

// region Parsing

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class ListBlock(val items: List<ListItem>) : Block
    data class Quote(val text: String) : Block
    data class Code(val code: String) : Block
    data class Image(val url: String, val alt: String) : Block
    data object Rule : Block
}

private data class ListItem(val indent: Int, val marker: String, val text: String)

private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
private val RULE = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val FENCE = Regex("^\\s*```")
private val IMAGE = Regex("^!\\[([^]\\n]*)]\\(([^)\\n]+?)\\)$")

private fun parseMarkdown(source: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = source.lines()
    var index = 0
    while (index < lines.size) {
        val raw = lines[index]
        val line = raw.trim()
        when {
            raw.isBlank() -> index++

            FENCE.containsMatchIn(raw) -> {
                val code = StringBuilder()
                index++
                while (index < lines.size && !FENCE.containsMatchIn(lines[index])) {
                    code.append(lines[index]).append('\n')
                    index++
                }
                index++ // Closing fence (or past the end).
                blocks += Block.Code(code.toString())
            }

            HEADING.containsMatchIn(line) -> {
                val match = HEADING.find(line)!!
                blocks += Block.Heading(match.groupValues[1].length, match.groupValues[2])
                index++
            }

            RULE.matches(line) -> {
                blocks += Block.Rule
                index++
            }

            IMAGE.matches(line) -> {
                val match = IMAGE.find(line)!!
                blocks += Block.Image(url = match.groupValues[2], alt = match.groupValues[1])
                index++
            }

            BULLET.containsMatchIn(raw) -> {
                val items = mutableListOf<ListItem>()
                while (index < lines.size && BULLET.containsMatchIn(lines[index])) {
                    val match = BULLET.find(lines[index])!!
                    items += ListItem(
                        indent = match.groupValues[1].length / 2,
                        marker = "•",
                        text = match.groupValues[2]
                    )
                    index++
                }
                blocks += Block.ListBlock(items)
            }

            NUMBERED.containsMatchIn(raw) -> {
                val items = mutableListOf<ListItem>()
                while (index < lines.size && NUMBERED.containsMatchIn(lines[index])) {
                    val match = NUMBERED.find(lines[index])!!
                    items += ListItem(
                        indent = match.groupValues[1].length / 2,
                        marker = "${match.groupValues[2]}.",
                        text = match.groupValues[3]
                    )
                    index++
                }
                blocks += Block.ListBlock(items)
            }

            line.startsWith(">") -> {
                val quote = StringBuilder()
                while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[index].trimStart().removePrefix(">").removePrefix(" "))
                    index++
                }
                blocks += Block.Quote(quote.toString())
            }

            else -> {
                // Paragraph: consecutive lines until a blank line or another
                // block's start. Newlines are kept (release-note authors
                // usually mean a break, not a markdown soft wrap).
                val paragraph = StringBuilder()
                while (index < lines.size && !isBlockStart(lines[index])) {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(lines[index].trim())
                    index++
                }
                blocks += Block.Paragraph(paragraph.toString())
            }
        }
    }
    return blocks
}

private fun isBlockStart(line: String): Boolean =
    line.isBlank() ||
        FENCE.containsMatchIn(line) ||
        HEADING.containsMatchIn(line) ||
        RULE.containsMatchIn(line) ||
        BULLET.containsMatchIn(line) ||
        NUMBERED.containsMatchIn(line) ||
        IMAGE.containsMatchIn(line) ||
        line.trimStart().startsWith(">")

// endregion

// region Inline spans

private enum class InlineKind { CODE, BOLD, STRIKE, LINK, ITALIC }

/** One inline markdown match: its kind, inner text, and url for links. */
private data class InlineMatch(val kind: InlineKind, val inner: String, val url: String? = null)

// Alternation order encodes priority: code first, then ** bold, then single-*
// italic; underscore variants are guarded against mid-word matches like
// file_name_ext. Inner text is re-parsed recursively, so nesting works.
private val INLINE_PATTERN = Regex(
    "`([^`\n]+)`" +                            // 1: code
        "|\\*\\*([^*\n]+?)\\*\\*" +            // 2: bold
        "|__(?!_)([^_\n]+?)__(?!_)" +          // 3: bold
        "|~~([^~\n]+?)~~" +                    // 4: strikethrough
        "|\\[([^]\n]+?)]\\(([^)\n]+?)\\)" +    // 5: link text, 6: url
        "|\\*([^*\n]+?)\\*" +                  // 7: italic
        "|(?<![\\w_])_([^_\n]+?)_(?![\\w_])"   // 8: italic
)

private class InlineStyles(
    val bold: SpanStyle,
    val italic: SpanStyle,
    val strike: SpanStyle,
    val code: SpanStyle,
    val link: SpanStyle
)

private fun MatchResult.toInlineMatch(): InlineMatch = when {
    groupValues[1].isNotEmpty() -> InlineMatch(InlineKind.CODE, groupValues[1])
    groupValues[2].isNotEmpty() -> InlineMatch(InlineKind.BOLD, groupValues[2])
    groupValues[3].isNotEmpty() -> InlineMatch(InlineKind.BOLD, groupValues[3])
    groupValues[4].isNotEmpty() -> InlineMatch(InlineKind.STRIKE, groupValues[4])
    groupValues[5].isNotEmpty() -> InlineMatch(InlineKind.LINK, groupValues[5], groupValues[6])
    groupValues[7].isNotEmpty() -> InlineMatch(InlineKind.ITALIC, groupValues[7])
    else -> InlineMatch(InlineKind.ITALIC, groupValues[8])
}

private fun appendInline(
    builder: AnnotatedString.Builder,
    text: String,
    base: SpanStyle?,
    styles: InlineStyles
) {
    var index = 0
    while (index < text.length) {
        val match = INLINE_PATTERN.find(text, index) ?: run {
            builder.append(text.substring(index))
            return
        }
        if (match.range.first > index) builder.append(text.substring(index, match.range.first))
        val parsed = match.toInlineMatch()
        index = match.range.last + 1
        when (parsed.kind) {
            InlineKind.CODE -> {
                builder.pushStyle((base ?: SpanStyle()).merge(styles.code))
                builder.append(parsed.inner)
                builder.pop()
            }

            InlineKind.BOLD -> appendInline(
                builder, parsed.inner, (base ?: SpanStyle()).merge(styles.bold), styles
            )

            InlineKind.ITALIC -> appendInline(
                builder, parsed.inner, (base ?: SpanStyle()).merge(styles.italic), styles
            )

            InlineKind.STRIKE -> appendInline(
                builder, parsed.inner, (base ?: SpanStyle()).merge(styles.strike), styles
            )

            InlineKind.LINK -> {
                val linkStyle = (base ?: SpanStyle()).merge(styles.link)
                val annotation = LinkAnnotation.Url(
                    parsed.url.orEmpty(),
                    TextLinkStyles(style = linkStyle)
                )
                builder.withLink(annotation) {
                    appendInline(this, parsed.inner, linkStyle, styles)
                }
            }
        }
    }
}

// endregion
