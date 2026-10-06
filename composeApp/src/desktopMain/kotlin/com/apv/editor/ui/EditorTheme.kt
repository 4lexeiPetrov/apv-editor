package com.apv.editor.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.apv.editor.syntax.TokenKind

object EditorTheme {
    val Background = Color(0xFF212121)
    val Surface = Color(0xFF1A1A1A)
    val SurfaceVariant = Color(0xFF262626)
    val Border = Color(0xFF303030)
    /** Input and menu outlines; one step lighter than [Border]. */
    val Outline = Color(0xFF424242)
    val OnSurface = Color(0xFFFAFAFA)
    val OnSurfaceMuted = Color(0xFFA8A8A8)
    val GutterText = Color(0xFF888888)
    val Accent = Color(0xFFFBCB45)

    /** Custom-drawn cross-line selection highlight in LargeEditorPane's passive lines (the active line's own selection uses BasicTextField's normal default instead). */
    val SelectionBackground = Accent.copy(alpha = 0.35f)

    val Keyword = Color(0xFFE91E63)
    val Type = Color(0xFF81D4FA)
    val String = Color(0xFFFFF59D)
    val Number = Color(0xFFE91E63)
    val Comment = Color(0xFF888888)
    val Annotation = Color(0xFFFFAB40)
    val Function = Color(0xFF9EE34F)
    val Heading = Color(0xFFE91E63)
    val Link = Color(0xFF81D4FA)
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

    /**
     * Every role Material 3 components read is set here, so dialogs, menus and buttons pick up the
     * editor palette instead of Material's default purple-tinted dark scheme.
     */
    val colorScheme = darkColorScheme(
        primary = Accent,
        onPrimary = Background,
        secondary = Accent,
        onSecondary = Background,
        background = Background,
        onBackground = OnSurface,
        surface = Surface,
        onSurface = OnSurface,
        surfaceVariant = SurfaceVariant,
        onSurfaceVariant = OnSurfaceMuted,
        surfaceContainerLowest = Surface,
        surfaceContainerLow = Background,
        surfaceContainer = SurfaceVariant,
        surfaceContainerHigh = SurfaceVariant,
        surfaceContainerHighest = Border,
        outline = Outline,
        outlineVariant = Border,
        error = BracketError,
        onError = Background,
    )

    /** Tighter corners than Material's defaults (which go up to 28dp), to suit a dense editor. */
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(8.dp),
        large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(12.dp),
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
