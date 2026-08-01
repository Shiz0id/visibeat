package com.visibeat.musicui.settings

import androidx.compose.runtime.Immutable

/**
 * What the offline radio can currently see, for the Settings panel.
 *
 * Plain strings, assembled by the host. music-ui cannot see the radio module —
 * it has no dependency on ONNX Runtime and should not gain one — so this is the
 * shape the information arrives in, the same arrangement as every other
 * cross-module wire in this app.
 *
 * Worth surfacing at all because every interesting failure in this feature is
 * silent. A model that did not load, an index that is empty, an index built by
 * a previous model: all three produce a Radio button that appears to do nothing.
 */
@Immutable
data class RadioStatus(
    /** Model id and load state, or why it is unavailable. */
    val modelLine: String = "Not checked",
    /** How much of the library has vectors. */
    val indexLine: String = "Unknown",
    /** Anything else worth reading — dimensions, preprocessing, last error. */
    val detail: List<String> = emptyList()
)
