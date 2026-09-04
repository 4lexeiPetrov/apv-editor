package com.apv.editor.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.apv.editor.syntax.TokenKind

object EditorTheme {
    val Background = Color(0xFF2C2832)
    val Surface = Color(0xFF3A3540)
    val SurfaceVariant = Color(0xFF403B4A)
    val Border = Color(0xFF4A4452)
    val OnSurface = Color(0xFFE6E1E8)
    val OnSurfaceMuted = Color(0xFF9E96A8)
    val GutterText = Color(0xFF6A6273)
    val Accent = Color(0xFFFBCB45)

    val Keyword = Color(0xFFF92672)
    val Type = Color(0xFF66D9EF)
    val String = Color(0xFFE6DB74)
    val Number = Color(0xFFAE81FF)
    val Comment = Color(0xFF75715E)
    val Annotation = Color(0xFFFD971F)
    val Function = Color(0xFFA6E22E)
    val Heading = Color(0xFFF92672)
    val Link = Color(0xFF66D9EF)
    val BracketError = Color(0xFFFF5370)

    /** Rainbow bracket palette, indexed by nesting depth (cycles via modulo). */
    val BracketColors = listOf(
        Color(0xFFFFD866), // gold
        Color(0xFFFF6AC1), // magenta
        Color(0xFF78DCE8), // cyan
        Color(0xFFA9DC76), // green
        Color(0xFFFC9867), // orange
        Color(0xFFAB9DF2), // violet
    )

    val colorScheme = darkColorScheme(
        primary = Accent,
        background = Background,
        surface = Surface,
        onBackground = OnSurface,
        onSurface = OnSurface,
    )

    fun styleFor(kind: TokenKind, depth: Int = -1): SpanStyle = when (kind) {
        TokenKind.KEYWORD -> SpanStyle(color = Keyword, fontWeight = FontWeight.SemiBold)
        TokenKind.TYPE -> SpanStyle(color = Type)
        TokenKind.STRING -> SpanStyle(color = String)
        TokenKind.NUMBER -> SpanStyle(color = Number)
        TokenKind.COMMENT -> SpanStyle(color = Comment)
        TokenKind.ANNOTATION -> SpanStyle(color = Annotation)
        TokenKind.FUNCTION -> SpanStyle(color = Function)
        TokenKind.HEADING -> SpanStyle(color = Heading, fontWeight = FontWeight.Bold)
        TokenKind.LINK -> SpanStyle(color = Link, textDecoration = TextDecoration.Underline)
        TokenKind.PUNCTUATION -> SpanStyle(
            color = if (depth >= 0) BracketColors[depth % BracketColors.size] else OnSurfaceMuted,
        )
        TokenKind.BRACKET_ERROR -> SpanStyle(color = BracketError, fontWeight = FontWeight.Bold)
    }
}
