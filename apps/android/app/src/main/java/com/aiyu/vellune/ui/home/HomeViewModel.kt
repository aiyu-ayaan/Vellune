package com.aiyu.vellune.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiyu.vellune.core.input.InputMode
import com.aiyu.vellune.data.AppSettings
import com.aiyu.vellune.data.CanvasDoc
import com.aiyu.vellune.data.CanvasRepository
import com.aiyu.vellune.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUi(
    val canvases: List<CanvasDoc>? = null,
    val stylus: Boolean = false,
    val mode: InputMode = InputMode.PenOnly,
    val prediction: Boolean = true,
)

class HomeViewModel(private val canvases: CanvasRepository, private val settings: SettingsRepository) : ViewModel() {

    val ui: StateFlow<HomeUi> = combine(canvases.canvases, settings.settings) { docs, s: AppSettings ->
        HomeUi(canvases = docs, stylus = settings.hasStylus(), mode = s.inputMode, prediction = s.prediction)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    /** Returns the new canvas id. */
    fun create(title: String): String = canvases.create(title).id

    fun rename(id: String, title: String) {
        title.trim().takeIf { it.isNotEmpty() }?.let { canvases.rename(id, it) }
    }

    fun delete(id: String) = canvases.delete(id)
}
