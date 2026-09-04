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
