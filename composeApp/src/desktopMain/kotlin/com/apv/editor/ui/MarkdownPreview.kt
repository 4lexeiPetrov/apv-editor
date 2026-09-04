package com.apv.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown

@Composable
fun MarkdownPreview(content: String, fontSize: Int, modifier: Modifier = Modifier) {
    MaterialTheme(colorScheme = EditorTheme.colorScheme) {
        ProvideTextStyle(
            value = TextStyle(
                color = EditorTheme.OnSurface,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.1).sp,
                fontFamily = FontFamily.Default,
            ),
        ) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(EditorTheme.Background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Markdown(content = content)
            }
        }
    }
}
