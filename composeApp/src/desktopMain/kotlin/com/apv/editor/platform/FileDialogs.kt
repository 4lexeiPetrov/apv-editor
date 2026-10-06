package com.apv.editor.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

object FileDialogs {

    fun openFiles(parent: Frame?, title: String = "Open File"): List<File> {
        val dialog = FileDialog(parent, title, FileDialog.LOAD).apply {
            isMultipleMode = true
            isVisible = true
        }
        return dialog.files?.toList() ?: emptyList()
    }

    /**
     * Folder picker for the project sidebar. On macOS the native AWT dialog only selects folders
     * while the `apple.awt.fileDialogForDirectories` property is set, so it is switched on just for
     * this call. Elsewhere AWT's FileDialog cannot pick folders at all, so Swing's chooser is used.
     */
    fun openFolder(parent: Frame?, title: String = "Open Folder"): File? {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac) {
            val chooser = javax.swing.JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            }
            val result = chooser.showOpenDialog(parent)
            return if (result == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(parent, title, FileDialog.LOAD).apply { isVisible = true }
            val dir = dialog.directory ?: return null
            val name = dialog.file ?: return null
            return File(dir, name).takeIf { it.isDirectory }
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    fun saveFile(parent: Frame?, suggestedName: String?, title: String = "Save As"): File? {
        val dialog = FileDialog(parent, title, FileDialog.SAVE).apply {
            if (suggestedName != null) file = suggestedName
            isVisible = true
        }
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return File(dir, name)
    }
}
