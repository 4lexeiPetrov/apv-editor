package com.apv.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apv.editor.*
import com.apv.editor.state.EditorState
import com.apv.editor.state.UnsavedDraft
import com.apv.editor.syntax.SyntaxDetector
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Unsaved buffers shown before "Show all": the list can hold many more. */
private const val UNSAVED_COLLAPSED_COUNT = 5

/** Rows shown per recent list. More are remembered (see EditorState.MAX_RECENT) so a dropped path can be replaced. */
private const val RECENT_VISIBLE_COUNT = 5

/** Below this width the actions stack above the lists instead of sitting beside them. */
private val TwoColumnMinWidth = 720.dp

private val RowShape = RoundedCornerShape(6.dp)
private val PanelShape = RoundedCornerShape(8.dp)

/**
 * What the editor area shows while no tab is open, which is also how the app always starts:
 * the basic actions on one side; unsaved buffers, recent folders and recent files on the other.
 * Nothing here opens by itself. A file or folder dropped on it is handled by the pane around it.
 */
@Composable
internal fun StartScreen(
    state: EditorState,
    onOpenFiles: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(EditorTheme.Background)) {
        val twoColumns = maxWidth >= TwoColumnMinWidth
        Box(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (twoColumns) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 888.dp)
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(72.dp),
                ) {
                    StartActions(state, onOpenFiles, onOpenFolder, Modifier.width(260.dp))
                    StartLists(state, Modifier.weight(1f))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    StartActions(state, onOpenFiles, onOpenFolder, Modifier.fillMaxWidth())
                    StartLists(state, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun StartActions(
    state: EditorState,
    onOpenFiles: () -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 10.dp)) {
            Text(
                text = stringResource(Res.string.app_name),
                color = EditorTheme.OnSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(Res.string.start_subtitle),
                color = EditorTheme.OnSurfaceMuted,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(28.dp))
        StartAction(Icons.Filled.Add, stringResource(Res.string.start_new_file), Platform.shortcutKeys("N")) {
            state.newTab()
        }
        StartAction(
            Icons.Outlined.Description,
            stringResource(Res.string.start_open_file),
            Platform.shortcutKeys("O"),
            onOpenFiles,
        )
        StartAction(
            Icons.Outlined.FolderOpen,
            stringResource(Res.string.start_open_folder),
            Platform.shortcutKeys("O", shift = true),
            onOpenFolder,
        )
        StartAction(
            Icons.Filled.Search,
            stringResource(Res.string.start_command_palette),
            Platform.shortcutKeys("P", shift = true),
        ) {
            state.paletteOpen = true
        }
    }
}

/** One "icon, action, key caps" row of the start screen's action list. */
@Composable
private fun StartAction(icon: ImageVector, label: String, keys: List<String>, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = EditorTheme.OnSurfaceMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = EditorTheme.OnSurface,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        for (keyName in keys) {
            Spacer(Modifier.width(4.dp))
            KeyCap(keyName)
        }
    }
}

@Composable
private fun KeyCap(text: String) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = Modifier
            .height(22.dp)
            .widthIn(min = 22.dp)
            .background(EditorTheme.Border, shape)
            .border(1.dp, EditorTheme.Outline, shape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = EditorTheme.OnSurfaceMuted, fontSize = 12.sp, lineHeight = 12.sp, maxLines = 1)
    }
}

@Composable
private fun StartLists(state: EditorState, modifier: Modifier = Modifier) {
    val nothingYet = state.unsavedDrafts.isEmpty() && state.recentFolders.isEmpty() && state.recentFiles.isEmpty()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (nothingYet) {
            DropHint()
        } else {
            if (state.unsavedDrafts.isNotEmpty()) UnsavedPanel(state)
            if (state.recentFolders.isNotEmpty()) {
                Column {
                    SectionHead(stringResource(Res.string.start_recent_folders))
                    for (path in state.recentFolders.take(RECENT_VISIBLE_COUNT)) {
                        key(path) {
                            RecentRow(name = displayName(path), detail = displayParent(path), onClick = {
                                state.openRecentFolder(path)
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = EditorTheme.OnSurfaceMuted,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (state.recentFiles.isNotEmpty()) {
                Column {
                    SectionHead(stringResource(Res.string.start_recent_files))
                    for (path in state.recentFiles.take(RECENT_VISIBLE_COUNT)) {
                        key(path) {
                            val name = displayName(path)
                            RecentRow(name = name, detail = displayParent(path), onClick = {
                                state.openRecentFile(path)
                            }) {
                                FileBadge(SyntaxDetector.detect(name))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHead(title: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(title, color = EditorTheme.OnSurfaceMuted, fontSize = 12.sp, maxLines = 1)
    }
}

/** A recent folder or file: [leading] icon, name, and where it lives, right-aligned. */
@Composable
private fun RecentRow(
    name: String,
    detail: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(10.dp))
        Text(name, color = EditorTheme.OnSurface, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(16.dp))
        Text(
            text = detail,
            color = EditorTheme.GutterText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The unsaved buffers kept from earlier sessions. The first few show; "Show all" opens the whole
 * list in a box that scrolls by itself, so a long list cannot push the recent lists off the screen.
 */
@Composable
private fun UnsavedPanel(state: EditorState) {
    val drafts = state.unsavedDrafts
    val total = drafts.size
    val overflows = total > UNSAVED_COLLAPSED_COUNT
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(EditorTheme.SurfaceVariant)
            .border(1.dp, EditorTheme.Border, PanelShape)
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(30.dp).padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.start_unsaved),
                color = EditorTheme.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(Res.string.start_unsaved_count, total),
                color = EditorTheme.OnSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .clip(RowShape)
                    .background(EditorTheme.Accent)
                    .clickable { state.restoreAllDrafts() }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.start_restore_all),
                    color = EditorTheme.Background,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }

        if (expanded && overflows) {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                for (draft in drafts) {
                    key(draft.draftPath) { DraftRow(draft, state) }
                }
            }
        } else {
            for (draft in drafts.take(UNSAVED_COLLAPSED_COUNT)) {
                key(draft.draftPath) { DraftRow(draft, state) }
            }
        }

        if (overflows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(RowShape)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = EditorTheme.OnSurfaceMuted,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (expanded) {
                        stringResource(Res.string.start_show_less)
                    } else {
                        stringResource(Res.string.start_show_all, total)
                    },
                    color = EditorTheme.OnSurface,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (!expanded) {
                    Text(
                        text = stringResource(Res.string.start_more, total - UNSAVED_COLLAPSED_COUNT),
                        color = EditorTheme.OnSurfaceMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** One unsaved buffer: click restores it into a tab, the cross asks to discard it. */
@Composable
private fun DraftRow(draft: UnsavedDraft, state: EditorState) {
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .clip(RowShape)
                .clickable { state.restoreDraft(draft) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Same marker as a modified tab.
            Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(7.dp).background(EditorTheme.Accent, CircleShape))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = draft.title,
                color = EditorTheme.OnSurface,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = draft.path?.let { displayParent(it) }.orEmpty(),
                color = EditorTheme.OnSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = editedLabel(draft.editedAt),
                color = EditorTheme.OnSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        ChromeIconButton(
            icon = Icons.Filled.Close,
            description = stringResource(Res.string.start_discard),
            onClick = { state.requestDraftDiscard(draft) },
            size = 22.dp,
            iconSize = 12.dp,
        )
    }
}

/** First launch, or nothing left to list: say what will appear here and that dropping works. */
@Composable
private fun DropHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .drawBehind {
                drawRoundRect(
                    color = EditorTheme.Outline,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = EditorTheme.OnSurfaceMuted,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(Res.string.start_drop_title),
            color = EditorTheme.OnSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.start_drop_text),
            color = EditorTheme.OnSurfaceMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

private val TimeOfDay: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DayName: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE")
private val DayAndMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** "Edited today, 23:41", "Edited yesterday", "Edited Sunday", "Edited 2 Oct"; empty when the time is unknown. */
@Composable
private fun editedLabel(editedAt: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(editedAt).atZone(zone)
    val daysAgo = ChronoUnit.DAYS.between(at.toLocalDate(), LocalDate.now(zone))
    return when {
        editedAt <= 0L -> ""
        daysAgo <= 0L -> stringResource(Res.string.start_edited_today, at.format(TimeOfDay))
        daysAgo == 1L -> stringResource(Res.string.start_edited_yesterday)
        daysAgo < 7L -> stringResource(Res.string.start_edited_on, at.format(DayName))
        else -> stringResource(Res.string.start_edited_on, at.format(DayAndMonth))
    }
}

/** Last path segment; the whole path for a root such as "/" or "C:\". */
private fun displayName(path: String): String = File(path).name.ifEmpty { path }

/** The folder a path lives in, with the home folder shortened to "~" where paths use "/". */
private fun displayParent(path: String): String {
    val parent = File(path).parent ?: return ""
    val home = System.getProperty("user.home").orEmpty()
    if (home.isEmpty() || File.separatorChar != '/') return parent
    return when {
        parent == home -> "~"
        parent.startsWith("$home/") -> "~" + parent.removePrefix(home)
        else -> parent
    }
}
