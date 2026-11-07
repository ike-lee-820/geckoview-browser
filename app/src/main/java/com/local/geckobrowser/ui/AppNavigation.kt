package com.local.geckobrowser.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.local.geckobrowser.browser.BrowserViewModel

object Routes {
    const val BROWSER = "browser"
    const val TABS = "tabs"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"
    const val PASSWORDS = "passwords"
    const val SETTINGS = "settings"
    const val SITE_SETTINGS = "site_settings"
    const val EXTENSIONS = "extensions"
}

@Composable
fun AppNavHost(
    viewModel: BrowserViewModel,
    voiceResult: String?,
    onVoiceResultConsumed: () -> Unit,
    fileResult: android.net.Uri?,
    onFileResultConsumed: () -> Unit,
    onRequestVoice: () -> Unit,
    onRequestFilePicker: () -> Unit,
    onEnterPip: () -> Unit,
    navController: NavHostController = rememberNavController()
) {
    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                vm = viewModel,
                nav = navController,
                voiceResult = voiceResult,
                onVoiceResultConsumed = onVoiceResultConsumed,
                fileResult = fileResult,
                onFileResultConsumed = onFileResultConsumed,
                onRequestVoice = onRequestVoice,
                onRequestFilePicker = onRequestFilePicker,
                onEnterPip = onEnterPip
            )
        }
        composable(Routes.TABS) { TabsScreen(vm = viewModel, nav = navController) }
        composable(Routes.BOOKMARKS) { BookmarksScreen(vm = viewModel, nav = navController) }
        composable(Routes.HISTORY) { HistoryScreen(vm = viewModel, nav = navController) }
        composable(Routes.DOWNLOADS) { DownloadsScreen(vm = viewModel, nav = navController) }
        composable(Routes.PASSWORDS) { PasswordsScreen(vm = viewModel, nav = navController) }
        composable(Routes.SETTINGS) { SettingsScreen(vm = viewModel, nav = navController) }
        composable(Routes.SITE_SETTINGS) { SiteSettingsScreen(vm = viewModel, nav = navController) }
        composable(Routes.EXTENSIONS) { ExtensionsScreen(vm = viewModel, nav = navController) }
    }
}
