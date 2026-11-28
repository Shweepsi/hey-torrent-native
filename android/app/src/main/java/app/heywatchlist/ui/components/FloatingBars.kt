package app.heywatchlist.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heywatchlist.ui.theme.HeyTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/*
 * Barres flottantes. Le contenu d'un écran occupe tout l'écran, barres système
 * comprises (bord à bord), et défile sous la barre du haut et sous la barre
 * d'onglets. Les deux barres floutent ce qui passe dessous :
 *
 * - le décor de l'app est la source de flou du dessous (`AppBackdrop`, plan 0) ;
 * - la liste qui défile en est une seconde, au-dessus ([underBars], plan 1) ;
 * - une barre posée hors de la liste floute les deux plans, dans l'ordre.
 *
 * Le verre des cartes, lui, reste dans la liste : il ne floute que le décor
 * (Haze ne prend, pour un effet posé dans une source, que les plans inférieurs).
 * Le contenu réserve la place des barres par ses marges ([barsPadding]) : au
 * repos rien n'est caché, au bout de la liste le dernier élément s'arrête
 * au-dessus de la barre d'onglets.
 */

/** Hauteur de la barre du haut, sous la barre d'état. */
val TopBarHeight = 56.dp

/**
 * Place de la barre d'onglets flottante au-dessus de la barre système (barre et
 * marges) ; zéro hors des onglets. Fournie par l'écran des onglets.
 */
val LocalTabBarSpace = compositionLocalOf { 0.dp }

/** Plan de la liste qui défile dans la pile de flou (le décor est au plan 0). */
private const val CONTENT_LAYER = 1f

/** Défilement (en px) au bout duquel la barre du haut est entièrement là. */
private const val SCROLL_REVEAL_PX = 120f

/**
 * Marges du contenu d'un écran bord à bord : barre d'état (et barre du haut si
 * [topBar]) en haut, barre système et barre d'onglets en bas, plus [top] et
 * [bottom].
 */
@Composable
fun barsPadding(horizontal: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 0.dp, topBar: Boolean = false): PaddingValues {
    val system = WindowInsets.systemBars.asPaddingValues()
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = system.calculateTopPadding() + (if (topBar) TopBarHeight else 0.dp) + top,
        bottom = system.calculateBottomPadding() + LocalTabBarSpace.current + bottom,
    )
}

/** Hauteur de la barre d'état, pour placer un élément juste dessous. */
@Composable
fun statusBarHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** Marque le contenu qui défile sous les barres flottantes : elles le floutent. */
fun Modifier.underBars(state: HazeState?): Modifier =
    if (state == null) this else hazeSource(state, zIndex = CONTENT_LAYER)

/** 0 tant que la liste est tout en haut, 1 une fois qu'elle a un peu défilé. */
fun LazyListState.scrolledFraction(): Float =
    if (firstVisibleItemIndex > 0) 1f else (firstVisibleItemScrollOffset / SCROLL_REVEAL_PX).coerceIn(0f, 1f)

/** Comme pour une liste, pour une grille. */
fun LazyGridState.scrolledFraction(): Float =
    if (firstVisibleItemIndex > 0) 1f else (firstVisibleItemScrollOffset / SCROLL_REVEAL_PX).coerceIn(0f, 1f)

/** Comme pour une liste, pour une colonne qui défile. */
fun ScrollState.scrolledFraction(): Float = (value / SCROLL_REVEAL_PX).coerceIn(0f, 1f)

/**
 * Fond d'une barre flottante, sous la barre d'état comprise : transparent tant
 * que [revealed] vaut 0 (contenu tout en haut), puis verre (flou du contenu qui
 * passe dessous, teinte couvrante, filet en bas) : la barre d'état et ce qui est
 * posé dans la barre restent lisibles sur une affiche claire comme sombre.
 * [revealed] est lu au dessin seulement : le défilement ne recompose rien.
 */
@Composable
fun GlassBar(
    revealed: () -> Float,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = LocalHazeState.current,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HeyTheme.colors
    val style = remember(colors) { colors.glassStyle(colors.chromeFill) }
    val glass = if (hazeState != null) {
        Modifier.hazeEffect(state = hazeState, style = style) { alpha = revealed() }
    } else {
        Modifier.drawBehind { drawRect(colors.glassFillFallback, alpha = revealed()) }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(glass)
                .drawBehind {
                    val line = 1.dp.toPx()
                    drawRect(
                        color = colors.glassBorder,
                        topLeft = Offset(0f, size.height - line),
                        size = Size(size.width, line),
                        alpha = revealed(),
                    )
                },
        )
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding(), content = content)
    }
}

/**
 * Barre du haut flottante d'un onglet. Au repos, seuls [navigation] et
 * [actions] s'y voient : le grand titre de l'écran est dans le contenu. Dès que
 * le contenu passe dessous, le verre apparaît avec [title] en petit, centré.
 */
@Composable
fun FloatingTopBar(
    title: String,
    revealed: () -> Float,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = LocalHazeState.current,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    GlassBar(revealed = revealed, modifier = modifier, hazeState = hazeState) {
        Box(modifier = Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 8.dp)) {
            // Le grand titre du contenu est déjà lu par TalkBack : celui-ci est décoratif.
            HeyText(
                text = title,
                style = HeyTheme.typography.title.copy(fontSize = 17.sp, lineHeight = 22.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 56.dp)
                    .graphicsLayer { alpha = revealed() }
                    .clearAndSetSemantics { },
            )
            Box(modifier = Modifier.align(Alignment.CenterStart)) { navigation?.invoke() }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                content = actions,
            )
        }
    }
}

/**
 * Écran de la pile dont l'en-tête (retour, champ de recherche, choix) flotte
 * au-dessus du contenu, qui défile dessous. L'en-tête est mesuré d'abord : le
 * contenu reçoit sa hauteur exacte (barre d'état comprise) pour sa marge du
 * haut, dès la première image, sans saut de mise en page.
 */
@Composable
fun FloatingHeaderLayout(header: @Composable () -> Unit, content: @Composable (headerHeight: Dp) -> Unit) {
    SubcomposeLayout(modifier = Modifier.fillMaxSize()) { constraints ->
        val headers = subcompose(HeaderSlot.Header, header).map { it.measure(constraints.copy(minHeight = 0)) }
        val height = headers.maxOfOrNull { it.height } ?: 0
        val bodies = subcompose(HeaderSlot.Body) { content(height.toDp()) }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            bodies.forEach { it.place(0, 0) }
            headers.forEach { it.place(0, 0) }
        }
    }
}

private enum class HeaderSlot { Header, Body }

/** Marges du contenu sous un en-tête flottant de [headerHeight], au-dessus de la barre système ou du clavier. */
@Composable
fun underHeaderPadding(headerHeight: Dp, horizontal: Dp = 16.dp, vertical: Dp = 8.dp): PaddingValues = PaddingValues(
    start = horizontal,
    end = horizontal,
    top = headerHeight + vertical,
    bottom = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding() + vertical,
)
