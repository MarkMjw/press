package me.saket.press.shared.preferences

import me.saket.press.shared.AndroidParcelize
import me.saket.press.shared.ui.ScreenKey

@AndroidParcelize
object PreferencesScreenKey : ScreenKey

@AndroidParcelize
data class PreferenceCategoryScreenKey(
  val category: PreferenceCategory
) : ScreenKey

data class PreferenceCategoryItemModel(
  val title: String,
  val subtitle: CharSequence,
  val category: PreferenceCategory
)

enum class PreferenceCategory {
  Editor,
  Theme,
  Sync,
  AboutApp
}
