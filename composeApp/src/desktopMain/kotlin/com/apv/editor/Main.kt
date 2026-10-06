package com.apv.editor

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.apv.editor.platform.AutosaveMode
import com.apv.editor.platform.FileDialogs
import com.apv.editor.platform.FileOpenEvents
import com.apv.editor.session.SessionStore
import com.apv.editor.session.WindowSnapshot
import com.apv.editor.state.*
import com.apv.editor.syntax.SyntaxLanguage
import com.apv.editor.ui.*
import kotlinx.coroutines.flow.debounce
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image
import java.awt.Frame
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

fun main(args: Array<String>) {

    if (Platform.isMac) System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    FileOpenEvents.install(args)
    application {
        val sessionStore = remember { SessionStore() }
        val initialSnapshot = remember { sessionStore.load() }

        val state = remember {
            EditorState().also { s ->
                s.draftReader = sessionStore::readDraft
                initialSnapshot?.let { s.applyStartup(it, sessionStore::draftModified) }
            }
        }

        DisposableEffect(state) {
            FileOpenEvents.setListener { file -> state.openFile(file) }
            onDispose {
                FileOpenEvents.clearListener()
                sessionStore.shutdown()
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
                    add(state.projectRoot)
                    add(state.sidebarVisible)
                    add(state.sidebarWidth)
                    add(state.expandedDirs.toList())
                    add(state.recentFolders.toList())
                    add(state.recentFiles.toList())
                    add(state.unsavedDrafts.toList())
                    add(windowState.size)
                    add(windowState.position)
                    for (pane in state.panes) {
                        add(pane.id)
                        add(pane.selectedIndex)
                        for (tab in pane.tabs) {
                            add(tab.id)
                            add(tab.file)
                            add(tab.language)
                            add(tab.previewMode)
                            add(tab.untitledIndex)
                            add(tab.textField)
                            add(tab.savedText)
                        }
                    }
                }
            }
                .debounce(250)
                .collect {
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
        val strOpenFolderTitle = stringResource(Res.string.dialog_open_folder)

        Window(
            onCloseRequest = {
                // Small-file tabs' unsaved edits survive quit via the draft snapshot the line
                // below captures. Large-file tabs don't get a draft (see EditorState.tabToSnapshot)
                // — the file on disk *is* their buffer — so anything dirty needs an actual save
                // here instead, rather than trusting the debounced autosave to have already fired.
                flushDirtyLargeTabs(state)
                sessionStore.schedule { state.toWrite(snapshotOfWindow(windowState)) }
                sessionStore.flush()
                exitApplication()
            },
            state = windowState,
            title = state.currentTab?.title ?: appName,
            icon = appIcon,
        ) {
            val parent: Frame? = window
            val windowScope: WindowScope = this

            // Unified title bar (macOS only): let the content draw under a transparent title bar
            // so the tab strip sits beside the traffic lights. Other platforms keep their native
            // title bar and none of this applies.
            val unifiedTitleBar = Platform.isMac
            if (unifiedTitleBar) {
                DisposableEffect(window) {
                    val rootPane = window.rootPane
                    rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    onDispose {}
                }
            }
            // The traffic lights are hidden in fullscreen, so nothing needs to step aside for them there.
            val titleBarInset = if (unifiedTitleBar && windowState.placement != WindowPlacement.Fullscreen) {
                TrafficLightInset
            } else {
                0.dp
            }

            val openFiles: () -> Unit = {
                FileDialogs.openFiles(parent, strOpenTitle).forEach { state.openFile(it) }
            }
            val openFolder: () -> Unit = {
                FileDialogs.openFolder(parent, strOpenFolderTitle)?.let { state.openProject(it) }
            }
            val saveCurrent: () -> Unit = {
                state.currentTab?.let { saveTab(it, state, parent, fmtSaved, strSaveTitle) }
            }
            val saveCurrentAs: () -> Unit = {
                state.currentTab?.let { saveTabAs(it, state, parent, fmtSaved, strSaveTitle) }
            }
            val commands = remember(state, parent, fmtSaved, strSaveTitle, strOpenTitle, strOpenFolderTitle) {
                buildCommands(state, openFiles, openFolder, saveCurrent, saveCurrentAs)
            }

            // AutosaveMode.OnWindowChange: save every dirty tab when the app is backgrounded
            // (Alt/Cmd-Tab away, click another app) — distinct from AutosaveMode.OnFocusChange,
            // which is per-tab and driven by EditorPane/LargeEditorPane's own composable lifecycle.
            DisposableEffect(window, state) {
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent) {
                        // Pick up edits made to open files by other programs while we were away.
                        state.checkExternalChanges()
                    }

                    override fun windowLostFocus(e: WindowEvent) {
                        state.autosaveAllOnWindowFocusLost()
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }

            MenuBar {
                Menu(stringResource(Res.string.menu_file), mnemonic = 'F') {
                    Item(stringResource(Res.string.menu_file_new), shortcut = Platform.shortcut(Key.N)) {
                        state.newTab()
                    }
                    Item(stringResource(Res.string.menu_file_open), shortcut = Platform.shortcut(Key.O)) {
                        openFiles()
                    }
                    Item(
                        stringResource(Res.string.menu_file_open_folder),
                        shortcut = Platform.shortcut(Key.O, shift = true)
                    ) {
                        openFolder()
                    }
                    Separator()
                    Item(stringResource(Res.string.menu_file_save), shortcut = Platform.shortcut(Key.S)) {
                        state.currentTab?.let { saveTab(it, state, parent, fmtSaved, strSaveTitle) }
                    }
                    Item(
                        stringResource(Res.string.menu_file_save_as),
                        shortcut = Platform.shortcut(Key.S, shift = true)
                    ) {
                        state.currentTab?.let { saveTabAs(it, state, parent, fmtSaved, strSaveTitle) }
                    }
                    Separator()
                    Item(stringResource(Res.string.menu_file_close_tab), shortcut = Platform.shortcut(Key.W)) {
                        state.closeTab(state.selectedIndex)
                    }
                }
                Menu(stringResource(Res.string.menu_edit), mnemonic = 'E') {
                    Item(stringResource(Res.string.menu_edit_undo), shortcut = Platform.shortcut(Key.Z)) {
                        state.undo()
                    }
                    Item(stringResource(Res.string.menu_edit_redo), shortcut = Platform.shortcut(Key.Z, shift = true)) {
                        state.redo()
                    }
                    Separator()
                    Item(stringResource(Res.string.menu_edit_find), shortcut = Platform.shortcut(Key.F)) {
                        state.openFind()
                    }
                }
                Menu(stringResource(Res.string.menu_view), mnemonic = 'V') {
                    Item(
                        stringResource(Res.string.menu_view_command_palette),
                        shortcut = Platform.shortcut(Key.P, shift = true)
                    ) {
                        state.paletteOpen = true
                    }
                    Item(stringResource(Res.string.menu_view_toggle_sidebar), shortcut = Platform.shortcut(Key.B)) {
                        state.toggleSidebar()
                    }
                    Separator()
                    Item(stringResource(Res.string.menu_view_increase_font), shortcut = Platform.shortcut(Key.Equals)) {
                        state.fontSize = (state.fontSize + 2).coerceAtMost(48)
                    }
                    Item(stringResource(Res.string.menu_view_decrease_font), shortcut = Platform.shortcut(Key.Minus)) {
                        state.fontSize = (state.fontSize - 2).coerceAtLeast(8)
                    }
                    Separator()
                    val markdownTab = state.currentTab?.takeIf { it.language == SyntaxLanguage.MARKDOWN }
                    CheckboxItem(
                        text = stringResource(Res.string.menu_view_markdown_preview),
                        checked = markdownTab?.showPreview == true,
                        enabled = markdownTab != null,
                        // Was Cmd+Shift+P, which now opens the command palette.
                        shortcut = Platform.shortcut(Key.V, shift = true),
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
                            shortcut = Platform.shortcut(Key.One, alt = true),
                            onClick = { state.applyLayout(PaneLayout.Single) },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.layout_columns2),
                            selected = state.layout == PaneLayout.Columns2,
                            shortcut = Platform.shortcut(Key.Two, alt = true),
                            onClick = { state.applyLayout(PaneLayout.Columns2) },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.layout_rows2),
                            selected = state.layout == PaneLayout.Rows2,
                            shortcut = Platform.shortcut(Key.Two, alt = true, shift = true),
                            onClick = { state.applyLayout(PaneLayout.Rows2) },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.layout_grid4),
                            selected = state.layout == PaneLayout.Grid2x2,
                            shortcut = Platform.shortcut(Key.Five, alt = true),
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
                    Separator()
                    Menu(stringResource(Res.string.menu_view_autosave), mnemonic = 'A') {
                        RadioButtonItem(
                            text = stringResource(Res.string.autosave_off),
                            selected = state.autosaveMode == AutosaveMode.Off,
                            onClick = { state.autosaveMode = AutosaveMode.Off },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.autosave_after_delay),
                            selected = state.autosaveMode is AutosaveMode.AfterDelay,
                            onClick = { state.autosaveMode = AutosaveMode.AfterDelay(1500) },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.autosave_on_focus_change),
                            selected = state.autosaveMode == AutosaveMode.OnFocusChange,
                            onClick = { state.autosaveMode = AutosaveMode.OnFocusChange },
                        )
                        RadioButtonItem(
                            text = stringResource(Res.string.autosave_on_window_change),
                            selected = state.autosaveMode == AutosaveMode.OnWindowChange,
                            onClick = { state.autosaveMode = AutosaveMode.OnWindowChange },
                        )
                    }
                }
            }

            CompositionLocalProvider(
                LocalUnifiedWindowScope provides (if (unifiedTitleBar) windowScope else null),
                LocalTitleBarInset provides titleBarInset,
            ) {
                App(
                    state = state,
                    onSaveTab = { tab -> saveTab(tab, state, parent, fmtSaved, strSaveTitle) },
                    onOpenInPane = { pane ->
                        FileDialogs.openFiles(parent, strOpenTitle).forEach { file -> state.openFile(pane, file) }
                    },
                    commands = commands,
                    onOpenFolder = openFolder,
                )
            }
        }
    }
}

/**
 * The command palette's entries. File actions that need a dialog are passed in, since they depend
 * on the window; everything else acts on [state] directly. Titles are English only for now.
 */
private fun buildCommands(
    state: EditorState,
    openFiles: () -> Unit,
    openFolder: () -> Unit,
    saveCurrent: () -> Unit,
    saveCurrentAs: () -> Unit,
): List<EditorCommand> {
    val hasTab = { state.currentTab != null }
    val hasEditableTab = { state.currentTab?.isLargeFile == false }
    val isMarkdown = {
        val tab = state.currentTab
        tab != null && !tab.isLargeFile && tab.language == SyntaxLanguage.MARKDOWN
    }

    fun format(title: String, failure: String, transform: (String) -> String?) = EditorCommand(
        title = "Format: $title",
        isEnabled = hasEditableTab,
        run = {
            val tab = state.currentTab
            if (tab != null) {
                state.statusMessage = if (tab.applyTextCommand(transform)) title else failure
                tab.editorFocusTick++
            }
        },
    )

    val out = ArrayList<EditorCommand>()
    out.add(EditorCommand("File: New Tab", Platform.shortcutLabel("N")) { state.newTab() })
    out.add(EditorCommand("File: Open File…", Platform.shortcutLabel("O"), run = openFiles))
    out.add(EditorCommand("File: Open Folder…", Platform.shortcutLabel("O", shift = true), run = openFolder))
    out.add(EditorCommand("File: Save", Platform.shortcutLabel("S"), hasTab, saveCurrent))
    out.add(EditorCommand("File: Save As…", Platform.shortcutLabel("S", shift = true), hasTab, saveCurrentAs))
    out.add(
        EditorCommand(
            "File: Close Tab",
            Platform.shortcutLabel("W"),
            hasTab
        ) { state.closeTab(state.selectedIndex) })
    out.add(EditorCommand("Edit: Find…", Platform.shortcutLabel("F"), hasEditableTab) { state.openFind() })
    out.add(EditorCommand("Edit: Undo", Platform.shortcutLabel("Z"), hasTab) { state.undo() })
    out.add(EditorCommand("Edit: Redo", Platform.shortcutLabel("Z", shift = true), hasTab) { state.redo() })
    out.add(format("Pretty-print JSON", "Not valid JSON, or already formatted", TextCommands::prettyPrintJson))
    out.add(format("Minify JSON", "Not valid JSON, or already minified", TextCommands::minifyJson))
    out.add(format("Sort lines", "Lines are already sorted", TextCommands::sortLines))
    out.add(format("Trim trailing whitespace", "No trailing whitespace", TextCommands::trimTrailingWhitespace))
    out.add(format("UPPER CASE", "Nothing to change", TextCommands::upperCase))
    out.add(format("lower case", "Nothing to change", TextCommands::lowerCase))
    out.add(EditorCommand("View: Toggle Sidebar", Platform.shortcutLabel("B")) { state.toggleSidebar() })
    out.add(EditorCommand("View: Increase Font", Platform.shortcutLabel("=")) {
        state.fontSize = (state.fontSize + 2).coerceAtMost(48)
    })
    out.add(EditorCommand("View: Decrease Font", Platform.shortcutLabel("-")) {
        state.fontSize = (state.fontSize - 2).coerceAtLeast(8)
    })
    for (mode in com.apv.editor.state.PreviewMode.entries) {
        out.add(EditorCommand("Markdown: ${mode.name}", isEnabled = isMarkdown) {
            state.currentTab?.previewMode = mode
        })
    }
    for (layout in PaneLayout.entries) {
        out.add(EditorCommand("Layout: ${layout.displayName}") { state.applyLayout(layout) })
    }
    for (language in SyntaxLanguage.entries) {
        out.add(EditorCommand("Syntax: ${language.displayName}", isEnabled = hasTab) {
            state.currentTab?.language = language
            state.statusMessage = "Syntax: ${language.displayName}"
        })
    }
    return out
}

private fun flushDirtyLargeTabs(state: EditorState) {
    for (pane in state.panes) {
        for (tab in pane.tabs) {
            if (tab.isLargeFile && tab.isModified) {
                try {
                    tab.save()
                } catch (_: Throwable) { /* best-effort; app is exiting either way */
                }
            }
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
    state.noteRecentFile(target)
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
