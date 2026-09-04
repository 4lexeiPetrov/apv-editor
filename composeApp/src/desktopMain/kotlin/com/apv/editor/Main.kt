package com.apv.editor

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.apv.editor.platform.FileDialogs
import com.apv.editor.platform.FileOpenEvents
import com.apv.editor.session.SessionStore
import com.apv.editor.session.WindowSnapshot
import com.apv.editor.state.EditorState
import com.apv.editor.state.PaneLayout
import com.apv.editor.state.TabState
import com.apv.editor.state.buildRestoredSession
import com.apv.editor.syntax.SyntaxLanguage
import com.apv.editor.ui.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image
import java.awt.Frame

fun main(args: Array<String>) {
    FileOpenEvents.install(args)
    application {
        val sessionStore = remember { SessionStore() }
        // Cheap: session.json holds only metadata (paths, cursors, layout, window geometry) — no
        // file contents — so this stays synchronous and the saved window size/position are ready
        // in time for rememberWindowState below.
        val initialSnapshot = remember { sessionStore.load() }

        // Start with a single blank tab so the window can appear immediately. The expensive part
        // of restoring a session — reading every previously-open file (and draft) off disk — runs
        // in the LaunchedEffect below, off the UI thread, and replaces this once it completes.
        val state = remember { EditorState().also { it.newTab() } }

        LaunchedEffect(initialSnapshot, sessionStore, state) {
            val snapshot = initialSnapshot ?: return@LaunchedEffect
            val restored = withContext(Dispatchers.IO) {
                buildRestoredSession(snapshot, sessionStore::readDraft)
            }
            state.applyRestored(restored)
        }

        DisposableEffect(state) {
            FileOpenEvents.setListener { file -> state.openFile(file) }
            onDispose {
                FileOpenEvents.clearListener()
                sessionStore.shutdown()
            }
        }
        val allPanesEmpty = state.panes.all { it.tabs.isEmpty() }
        LaunchedEffect(allPanesEmpty) {
            if (allPanesEmpty) {
                sessionStore.flush()
                exitApplication()
            }
        }
        val initialWindow = initialSnapshot?.window
        val initialPosition = if (initialWindow?.x != null && initialWindow.y != null) {
            WindowPosition(initialWindow.x.dp, initialWindow.y.dp)
        } else {
            WindowPosition.PlatformDefault
        }
        val windowState: WindowState = rememberWindowState(
            width = (initialWindow?.width ?: 1100).dp,
            height = (initialWindow?.height ?: 760).dp,
            position = initialPosition,
        )

        LaunchedEffect(state, sessionStore, windowState) {
            snapshotFlow {
                buildList<Any?> {
                    add(state.layout)
                    add(state.fontSize)
                    add(state.focusedPaneId)
                    add(windowState.size)
                    add(windowState.position)
                    for (pane in state.panes) {
                        add(pane.id)
                        add(pane.selectedIndex)
                        for (tab in pane.tabs) {
                            add(tab.id)
                            add(tab.file)
                            add(tab.language)
                            add(tab.showPreview)
                            add(tab.textField)
                            add(tab.savedText)
                        }
                    }
                }
            }.collect {
                sessionStore.schedule { state.toWrite(snapshotOfWindow(windowState)) }
            }
        }

        val appIcon = remember {
            useResource("icons/icon-512.png") { stream ->
                BitmapPainter(Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap())
            }
        }

        // Pre-resolve strings needed outside Composable context (click handlers, helpers).
        val appName = stringResource(Res.string.app_name)
        val fmtSaved = stringResource(Res.string.status_saved)
        val fmtSyntax = stringResource(Res.string.status_syntax)
        val strMdOn = stringResource(Res.string.status_markdown_preview_enabled)
        val strMdOff = stringResource(Res.string.status_markdown_preview_disabled)
        val strOpenTitle = stringResource(Res.string.dialog_open_file)
        val strSaveTitle = stringResource(Res.string.dialog_save_as)

        Window(
            onCloseRequest = {
                sessionStore.schedule { state.toWrite(snapshotOfWindow(windowState)) }
                sessionStore.flush()
                exitApplication()
            },
            state = windowState,
            title = state.currentTab?.title ?: appName,
            icon = appIcon,
        ) {
            val parent: Frame? = window

            MenuBar {
                Menu(stringResource(Res.string.menu_file), mnemonic = 'F') {
                    Item(stringResource(Res.string.menu_file_new), shortcut = KeyShortcut(Key.N, meta = true)) {
                        state.newTab()
                    }
                    Item(stringResource(Res.string.menu_file_open), shortcut = KeyShortcut(Key.O, meta = true)) {
                        FileDialogs.openFiles(parent, strOpenTitle).forEach { state.openFile(it) }
                    }
                    Separator()
                    Item(stringResource(Res.string.menu_file_save), shortcut = KeyShortcut(Key.S, meta = true)) {
                        state.currentTab?.let { saveTab(it, state, parent, fmtSaved, strSaveTitle) }
                    }
                    Item(stringResource(Res.string.menu_file_save_as), shortcut = KeyShortcut(Key.S, meta = true, shift = true)) {
                        state.currentTab?.let { saveTabAs(it, state, parent, fmtSaved, strSaveTitle) }
                    }
                    Separator()
                    Item(stringResource(Res.string.menu_file_close_tab), shortcut = KeyShortcut(Key.W, meta = true)) {
                        state.closeTab(state.selectedIndex)
                    }
                }
                Menu(stringResource(Res.string.menu_edit), mnemonic = 'E') {
                    Item(stringResource(Res.string.menu_edit_undo), shortcut = KeyShortcut(Key.Z, meta = true)) {
                        state.undo()
                    }
                    Item(stringResource(Res.string.menu_edit_redo), shortcut = KeyShortcut(Key.Z, meta = true, shift = true)) {
                        state.redo()
                    }
                }
                Menu(stringResource(Res.string.menu_view), mnemonic = 'V') {
                    Item(stringResource(Res.string.menu_view_increase_font), shortcut = KeyShortcut(Key.Equals, meta = true)) {
                        state.fontSize = (state.fontSize + 2).coerceAtMost(48)
                    }
                    Item(stringResource(Res.string.menu_view_decrease_font), shortcut = KeyShortcut(Key.Minus, meta = true)) {
                        state.fontSize = (state.fontSize - 2).coerceAtLeast(8)
                    }
                    Separator()
                    val markdownTab = state.currentTab?.takeIf { it.language == SyntaxLanguage.MARKDOWN }
                    CheckboxItem(
                        text = stringResource(Res.string.menu_view_markdown_preview),
                        checked = markdownTab?.showPreview == true,
                        enabled = markdownTab != null,
                        shortcut = KeyShortcut(Key.P, meta = true, shift = true),
                        onCheckedChange = { checked ->
                            markdownTab?.showPreview = checked
                            state.statusMessage = if (checked) strMdOn else strMdOff
                        },
                    )
                    Separator()
                    Menu(stringResource(Res.string.menu_view_layout), mnemonic = 'L') {
                        RadioButtonItem(
                            text = stringResource(Res.string.layout_single),
                            selected = state.layout == PaneLayout.Single,
                            shortcut = KeyShortcut(Key.One, meta = true, alt = true),
                            onClick = { state.applyLayout(PaneLayout.Single) },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.layout_columns2),
                            selected = state.layout == PaneLayout.Columns2,
                            shortcut = KeyShortcut(Key.Two, meta = true, alt = true),
                            onClick = { state.applyLayout(PaneLayout.Columns2) },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.layout_rows2),
                            selected = state.layout == PaneLayout.Rows2,
                            shortcut = KeyShortcut(Key.Two, meta = true, alt = true, shift = true),
                            onClick = { state.applyLayout(PaneLayout.Rows2) },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.layout_grid4),
                            selected = state.layout == PaneLayout.Grid2x2,
                            shortcut = KeyShortcut(Key.Five, meta = true, alt = true),
                            onClick = { state.applyLayout(PaneLayout.Grid2x2) },
                        )
                    }
                    Separator()
                    Menu(stringResource(Res.string.menu_view_syntax), mnemonic = 'S') {
                        val currentLanguage = state.currentTab?.language
                        SyntaxLanguage.entries.forEach { lang ->
                            RadioButtonItem(
                                text = lang.displayName,
                                selected = currentLanguage == lang,
                                onClick = {
                                    val tab = state.currentTab ?: return@RadioButtonItem
                                    tab.language = lang
                                    state.statusMessage = fmtSyntax.format(lang.displayName)
                                },
                            )
                        }
                    }
                }
            }

            App(
                state = state,
                onSaveTab = { tab -> saveTab(tab, state, parent, fmtSaved, strSaveTitle) },
                onOpenInPane = { pane ->
                    FileDialogs.openFiles(parent, strOpenTitle).forEach { file -> state.openFile(pane, file) }
                },
            )
        }
    }
}

private fun saveTab(
    tab: TabState,
    state: EditorState,
    parent: Frame?,
    fmtSaved: String,
    saveDialogTitle: String,
): Boolean =
    if (tab.file == null) {
        saveTabAs(tab, state, parent, fmtSaved, saveDialogTitle)
    } else {
        tab.save()
        state.statusMessage = fmtSaved.format(tab.file?.name ?: "")
        true
    }

private fun saveTabAs(
    tab: TabState,
    state: EditorState,
    parent: Frame?,
    fmtSaved: String,
    saveDialogTitle: String,
): Boolean {
    val target = FileDialogs.saveFile(parent, tab.file?.name, saveDialogTitle) ?: return false
    tab.saveAs(target)
    state.statusMessage = fmtSaved.format(target.name)
    return true
}

private fun snapshotOfWindow(windowState: WindowState): WindowSnapshot {
    val size = windowState.size
    val pos = windowState.position
    val (x, y) = if (pos is WindowPosition.Absolute) {
        pos.x.value.toInt() to pos.y.value.toInt()
    } else {
        null to null
    }
    return WindowSnapshot(
        width = size.width.value.toInt().coerceAtLeast(200),
        height = size.height.value.toInt().coerceAtLeast(200),
        x = x,
        y = y,
    )
}
