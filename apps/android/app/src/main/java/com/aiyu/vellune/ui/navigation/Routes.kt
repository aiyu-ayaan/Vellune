package com.aiyu.vellune.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object HomeKey : NavKey

@Serializable
data class CanvasKey(val id: String) : NavKey

@Serializable
data object TracesKey : NavKey

@Serializable
data object SettingsKey : NavKey
