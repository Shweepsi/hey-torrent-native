package app.heywatchlist.admin

import android.net.Uri
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyIcon
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.HeySegmented
import app.heywatchlist.ui.components.HeySpinner
import app.heywatchlist.ui.components.HeyStatusLine
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.HeyTextField
import app.heywatchlist.ui.components.StatusKind
import app.heywatchlist.ui.components.StatusPill
import app.heywatchlist.ui.components.StatusTone
import app.heywatchlist.ui.components.glassEdge
import app.heywatchlist.ui.theme.HeyTheme

/** Ce que montre la feuille d'édition ouverte. */
private sealed interface Editing {
    val key: String

    data class Source(val id: String) : Editing {
        override val key = "source:$id"
    }

    data object Qbit : Editing {
        override val key = "qbit"
    }

    data object Sorting : Editing {
        override val key = "sorting"
    }

    companion object {
        fun from(key: String?): Editing? = when {
            key == null -> null
            key == Qbit.key -> Qbit
            key == Sorting.key -> Sorting
            key.startsWith("source:") -> Source(key.removePrefix("source:"))
            else -> null
        }
    }
}

/**
 * Réglages des serveurs : une ligne par service avec son état (le test part
 * seul à l'ouverture), et une feuille pour modifier ses champs. Rien n'est
 * préconfiguré : chaque source est saisie par l'administrateur. En bas, le
 * lien avec Plex ([plexCard], l'adresse des webhooks).
 */
@Composable
fun ServersScreen(viewModel: ServersViewModel, plexCard: @Composable () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = viewModel.form
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val close = { editingKey = null }

    LaunchedEffect(state.loading) {
        if (!state.loading) viewModel.testUntested()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AdminHeader(title = stringResource(R.string.admin_tab_servers))
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (state.loading) item { LoadingRow(stringResource(R.string.admin_servers_syncing)) }
            state.syncFailure?.let { failure ->
                item {
                    AdminCard {
                        FailureText(failure)
                        HeyText(text = stringResource(R.string.admin_servers_local_copy), style = HeyTheme.typography.caption)
                    }
                }
            }
            state.saveFailure?.let { item { FailureText(it) } }
            if (state.saved) {
                item { HeyStatusLine(text = stringResource(R.string.admin_servers_saved), kind = StatusKind.Success) }
            }

            item { SectionLabel(stringResource(R.string.admin_servers_section_search)) }
            item {
                ServiceGroup {
                    form.sources.forEach { source ->
                        ServiceRow(
                            title = source.name.ifBlank { stringResource(R.string.admin_source_unnamed) },
                            subtitle = if (source.enabled) hostOf(source.url) else stringResource(R.string.admin_disabled),
                            badge = { InitialAvatar(name = source.name.ifBlank { "?" }, size = 36.dp) },
                            status = { if (source.enabled) ConnectionPill(state.sourceTests[source.id]) },
                            onClick = { editingKey = Editing.Source(source.id).key },
                        )
                        RowDivider()
                    }
                    HeyButton(
                        text = stringResource(R.string.admin_source_add),
                        onClick = {
                            editingKey = Editing.Source(viewModel.addSource()).key
                        },
                        kind = HeyButtonKind.Link,
                        icon = HeyIcons.Plus,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.admin_servers_section_download)) }
            item {
                ServiceGroup {
                    ServiceRow(
                        title = stringResource(R.string.admin_qbit_title),
                        subtitle = form.qbitUrl.takeIf { it.isNotBlank() }?.let(::hostOf)
                            ?: stringResource(R.string.admin_not_configured),
                        badge = { IconBadge(HeyIcons.Download) },
                        status = { ConnectionPill(state.qbitTest) },
                        onClick = { editingKey = Editing.Qbit.key },
                    )
                    RowDivider()
                    ServiceRow(
                        title = stringResource(R.string.admin_sorting_title),
                        subtitle = sortingSummary(form),
                        badge = { IconBadge(HeyIcons.Sliders) },
                        status = {},
                        onClick = { editingKey = Editing.Sorting.key },
                    )
                }
            }
        }
    }

    val save = {
        viewModel.save()
        close()
    }
    when (val editing = Editing.from(editingKey)) {
        null -> Unit
        is Editing.Source -> {
            val source = form.sources.firstOrNull { it.id == editing.id }
            if (source == null) {
                close()
            } else {
                SourceSheet(
                    source = source,
                    test = state.sourceTests[source.id],
                    saving = state.saving,
                    onChange = { change -> viewModel.editSource(source.id, change) },
                    onTest = { viewModel.testSource(source.id) },
                    onRemove = {
                        viewModel.removeSource(source.id)
                        close()
                    },
                    onSave = save,
                    onDismiss = close,
                )
            }

            // Hors du formulaire : l'adresse des webhooks s'enregistre seule.
            item { SectionLabel(stringResource(R.string.admin_plex_title)) }
            item { plexCard() }
        }
        Editing.Qbit -> QbitSheet(
            form = form,
            test = state.qbitTest,
            saving = state.saving,
            onChange = viewModel::edit,
            onTest = viewModel::testQbittorrent,
            onSave = save,
            onDismiss = close,
        )
        Editing.Sorting -> SortingSheet(form = form, saving = state.saving, onChange = viewModel::edit, onSave = save, onDismiss = close)
    }
}

/** Titre d'un groupe de lignes. */
@Composable
private fun SectionLabel(text: String) {
    HeyText(
        text = text,
        style = HeyTheme.typography.label,
        color = HeyTheme.colors.onBackgroundMuted,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp).semantics { heading() },
    )
}

/** Groupe de lignes sur une même surface de verre (sans flou : la liste défile). */
@Composable
private fun ServiceGroup(content: @Composable () -> Unit) {
    val colors = HeyTheme.colors
    val shape = HeyTheme.shapes.card
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, glassEdge(colors), shape)
            .padding(vertical = 4.dp),
    ) { content() }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .padding(start = 66.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(HeyTheme.colors.glassBorder),
    )
}

/** Un service : pastille, nom, adresse, état ; la ligne entière ouvre sa feuille. */
@Composable
private fun ServiceRow(
    title: String,
    subtitle: String,
    badge: @Composable () -> Unit,
    status: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(indication = LocalIndication.current, interactionSource = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        badge()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HeyText(text = title, style = HeyTheme.typography.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HeyText(
                text = subtitle,
                style = HeyTheme.typography.caption,
                color = HeyTheme.colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        status()
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    val colors = HeyTheme.colors
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(colors.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        HeyIcon(icon = icon, contentDescription = null, tint = colors.primary, iconSize = 20.dp)
    }
}

/** État d'une connexion en pastille : rien tant qu'aucun test n'est parti. */
@Composable
private fun ConnectionPill(test: ConnectionTest?) {
    when (test) {
        null -> Unit
        ConnectionTest.Running -> HeySpinner(description = stringResource(R.string.admin_testing))
        is ConnectionTest.Passed -> StatusPill(
            text = test.detail.takeIf { it.isNotEmpty() } ?: stringResource(R.string.admin_status_ok),
            tone = StatusTone.Success,
            icon = HeyIcons.Check,
        )
        is ConnectionTest.SourceFailed, is ConnectionTest.Failed -> StatusPill(
            text = stringResource(R.string.admin_status_failed),
            tone = StatusTone.Danger,
            icon = HeyIcons.Alert,
        )
    }
}

/** L'hôte d'une adresse, pour la ligne : « qbit.example.com ». */
private fun hostOf(url: String): String = Uri.parse(url.trim()).host?.takeIf { it.isNotEmpty() } ?: url.trim()

/** « films · series · 2 termes exclus ». */
@Composable
private fun sortingSummary(form: ServerForm): String {
    val excluded = form.excludedTerms.split(',').count { it.isNotBlank() }
    val parts = listOf(
        form.movieCategory.ifBlank { ServerForm.DEFAULT_MOVIE_CATEGORY },
        form.seriesCategory.ifBlank { ServerForm.DEFAULT_SERIES_CATEGORY },
    ) + listOfNotNull(excluded.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.admin_excluded_count, it, it) })
    return parts.joinToString(" · ")
}

/** Champ précédé de son libellé, toujours visible (le texte d'exemple disparaît à la saisie). */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HeyText(text = label, style = HeyTheme.typography.caption, color = HeyTheme.colors.onBackgroundMuted)
        HeyTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            label = label,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else keyboardType,
                autoCorrectEnabled = false,
            ),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

/** Bas commun des feuilles : résultat du test, puis « Tester » et « Enregistrer ». */
@Composable
private fun SheetActions(test: ConnectionTest?, canTest: Boolean, saving: Boolean, onTest: (() -> Unit)?, onSave: () -> Unit) {
    TestResult(test)
    ButtonRow { cell ->
        if (onTest != null) {
            HeyButton(
                text = stringResource(if (test == ConnectionTest.Running) R.string.admin_testing else R.string.admin_test),
                onClick = onTest,
                kind = HeyButtonKind.Secondary,
                enabled = test != ConnectionTest.Running && canTest,
                modifier = cell,
            )
        }
        HeyButton(
            text = stringResource(if (saving) R.string.admin_servers_saving else R.string.admin_servers_save),
            onClick = onSave,
            enabled = !saving,
            modifier = cell,
        )
    }
}

@Composable
private fun SourceSheet(
    source: SourceForm,
    test: ConnectionTest?,
    saving: Boolean,
    onChange: ((SourceForm) -> SourceForm) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdminDialog(
        title = source.name.ifBlank { stringResource(R.string.admin_source_unnamed) },
        body = stringResource(R.string.admin_sources_body),
        onDismiss = onDismiss,
    ) {
        LabeledField(
            label = stringResource(R.string.admin_source_name),
            value = source.name,
            onValueChange = { value -> onChange { it.copy(name = value) } },
            placeholder = stringResource(R.string.admin_source_name_placeholder),
        )
        LabeledField(
            label = stringResource(R.string.admin_source_url),
            value = source.url,
            onValueChange = { value -> onChange { it.copy(url = value) } },
            placeholder = stringResource(R.string.admin_source_url_placeholder),
            keyboardType = KeyboardType.Uri,
        )
        LabeledField(
            label = stringResource(R.string.admin_source_key),
            value = source.apiKey,
            onValueChange = { value -> onChange { it.copy(apiKey = value) } },
            placeholder = stringResource(R.string.admin_source_key_placeholder),
            secret = true,
        )
        HeySegmented(
            options = listOf(stringResource(R.string.admin_enabled), stringResource(R.string.admin_disabled)),
            selectedIndex = if (source.enabled) 0 else 1,
            onSelect = { index -> onChange { it.copy(enabled = index == 0) } },
            modifier = Modifier.fillMaxWidth(),
        )
        SheetActions(test = test, canTest = source.url.isNotBlank(), saving = saving, onTest = onTest, onSave = onSave)
        HeyButton(
            text = stringResource(R.string.admin_source_remove),
            onClick = onRemove,
            kind = HeyButtonKind.DangerLink,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QbitSheet(
    form: ServerForm,
    test: ConnectionTest?,
    saving: Boolean,
    onChange: ((ServerForm) -> ServerForm) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdminDialog(
        title = stringResource(R.string.admin_qbit_title),
        body = stringResource(R.string.admin_qbit_body),
        onDismiss = onDismiss,
    ) {
        LabeledField(
            label = stringResource(R.string.admin_qbit_url),
            value = form.qbitUrl,
            onValueChange = { value -> onChange { it.copy(qbitUrl = value) } },
            placeholder = stringResource(R.string.admin_qbit_url_placeholder),
            keyboardType = KeyboardType.Uri,
        )
        HeySegmented(
            options = listOf(stringResource(R.string.admin_qbit_auth_password), stringResource(R.string.admin_qbit_auth_token)),
            selectedIndex = if (form.qbitUsesToken) 1 else 0,
            onSelect = { index -> onChange { it.copy(qbitUsesToken = index == 1) } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (form.qbitUsesToken) {
            LabeledField(
                label = stringResource(R.string.admin_qbit_token),
                value = form.qbitToken,
                onValueChange = { value -> onChange { it.copy(qbitToken = value) } },
                placeholder = stringResource(R.string.admin_qbit_token_placeholder),
                secret = true,
            )
        } else {
            LabeledField(
                label = stringResource(R.string.admin_qbit_username),
                value = form.qbitUsername,
                onValueChange = { value -> onChange { it.copy(qbitUsername = value) } },
                placeholder = stringResource(R.string.admin_qbit_username_placeholder),
            )
            LabeledField(
                label = stringResource(R.string.admin_qbit_password),
                value = form.qbitPassword,
                onValueChange = { value -> onChange { it.copy(qbitPassword = value) } },
                placeholder = stringResource(R.string.admin_qbit_password_placeholder),
                secret = true,
            )
        }
        SheetActions(test = test, canTest = form.qbitUrl.isNotBlank(), saving = saving, onTest = onTest, onSave = onSave)
    }
}

@Composable
private fun SortingSheet(
    form: ServerForm,
    saving: Boolean,
    onChange: ((ServerForm) -> ServerForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdminDialog(title = stringResource(R.string.admin_sorting_title), onDismiss = onDismiss) {
        LabeledField(
            label = stringResource(R.string.admin_movie_category),
            value = form.movieCategory,
            onValueChange = { value -> onChange { it.copy(movieCategory = value) } },
            placeholder = ServerForm.DEFAULT_MOVIE_CATEGORY,
        )
        LabeledField(
            label = stringResource(R.string.admin_series_category),
            value = form.seriesCategory,
            onValueChange = { value -> onChange { it.copy(seriesCategory = value) } },
            placeholder = ServerForm.DEFAULT_SERIES_CATEGORY,
        )
        LabeledField(
            label = stringResource(R.string.admin_excluded_terms),
            value = form.excludedTerms,
            onValueChange = { value -> onChange { it.copy(excludedTerms = value) } },
            placeholder = stringResource(R.string.admin_excluded_terms_placeholder),
        )
        SheetActions(test = null, canTest = false, saving = saving, onTest = null, onSave = onSave)
    }
}

@Composable
private fun TestResult(test: ConnectionTest?) {
    when (test) {
        null, ConnectionTest.Running -> Unit
        is ConnectionTest.Passed -> HeyStatusLine(
            text = if (test.detail.isEmpty()) {
                stringResource(R.string.admin_test_passed)
            } else {
                stringResource(R.string.admin_test_passed_version, test.detail)
            },
            kind = StatusKind.Success,
        )
        is ConnectionTest.SourceFailed -> HeyStatusLine(text = stringResource(test.failure.labelRes()), kind = StatusKind.Failure)
        is ConnectionTest.Failed -> FailureText(test.failure)
    }
}
