package app.heywatchlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.heywatchlist.ui.theme.HeyTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Fond commun des écrans : le décor est la source du flou, et les surfaces en
 * verre du [content] le floutent par [LocalHazeState].
 */
@Composable
fun AppBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val hazeState = rememberHazeState()
    Box(modifier = modifier.fillMaxSize().background(HeyTheme.colors.background)) {
        AuroraBackground(Modifier.fillMaxSize().hazeSource(hazeState))
        CompositionLocalProvider(LocalHazeState provides hazeState, content = content)
    }
}
