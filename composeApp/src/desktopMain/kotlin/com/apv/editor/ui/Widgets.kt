package com.apv.editor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apv.editor.syntax.SyntaxLanguage

/** Shows [text] in a small light tooltip after a short hover over [content]. A blank [text] shows nothing. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HintTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (text.isBlank()) {
        Box(modifier) { content() }
        return
    }
    TooltipArea(
        modifier = modifier,
        tooltip = {
            Box(
                modifier = Modifier
                    .background(Color(0xFFE6E6E6), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(text, color = Color(0xFF212121), fontSize = 11.sp, maxLines = 1)
            }
        },
        delayMillis = 600,
        content = content,
    )
}

/** A compact square icon button for the tab bar, find bar and sidebar (Material's IconButton is 48dp, too big here). */
@Composable
internal fun ChromeIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    iconSize: Dp = 14.dp,
    tint: Color = EditorTheme.OnSurfaceMuted,
    selected: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) EditorTheme.Border else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) EditorTheme.OnSurface else tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** The small coloured square with one letter that marks a file's language in tabs and the sidebar. */
@Composable
internal fun FileBadge(language: SyntaxLanguage, modifier: Modifier = Modifier, scale: Float = 1f) {
    Box(
        modifier = modifier
            .size((14 * scale).dp)
            .background(badgeColor(language), RoundedCornerShape((3 * scale).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badgeLetter(language),
            color = EditorTheme.Background,
            fontSize = (9 * scale).sp,
            lineHeight = (9 * scale).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private fun badgeLetter(language: SyntaxLanguage): String = when (language) {
    SyntaxLanguage.NONE -> "T"
    SyntaxLanguage.C_FAMILY -> "C"
    SyntaxLanguage.CSHARP -> "#"
    else -> language.displayName.take(1).uppercase()
}

private fun badgeColor(language: SyntaxLanguage): Color = when (language) {
    SyntaxLanguage.NONE -> EditorTheme.OnSurfaceMuted
    SyntaxLanguage.KOTLIN, SyntaxLanguage.TYPESCRIPT, SyntaxLanguage.GO, SyntaxLanguage.C_FAMILY,
    SyntaxLanguage.CSHARP, SyntaxLanguage.CSS, SyntaxLanguage.SQL -> EditorTheme.Type
    SyntaxLanguage.MARKDOWN, SyntaxLanguage.SHELL, SyntaxLanguage.YAML, SyntaxLanguage.PYTHON,
    SyntaxLanguage.LUA -> EditorTheme.Function
    SyntaxLanguage.JSON, SyntaxLanguage.JAVASCRIPT, SyntaxLanguage.XML, SyntaxLanguage.HTML -> EditorTheme.String
    SyntaxLanguage.JAVA, SyntaxLanguage.RUST, SyntaxLanguage.SWIFT, SyntaxLanguage.GROOVY,
    SyntaxLanguage.SCALA, SyntaxLanguage.PHP -> EditorTheme.Annotation
    SyntaxLanguage.RUBY, SyntaxLanguage.CLOJURE -> EditorTheme.Keyword
}
