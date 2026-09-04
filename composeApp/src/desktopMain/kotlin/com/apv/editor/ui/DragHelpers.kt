package com.apv.editor.ui

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

internal val TabDragFlavor = DataFlavor(
    "application/x-apv-tab-drag; class=java.lang.String",
    "APV Tab Drag",
)

internal fun Transferable.hasFiles(): Boolean = runCatching {
    isDataFlavorSupported(DataFlavor.javaFileListFlavor)
}.getOrDefault(false)

internal fun Transferable.hasTabDrag(): Boolean = runCatching {
    isDataFlavorSupported(TabDragFlavor)
}.getOrDefault(false)
