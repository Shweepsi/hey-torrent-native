package app.heywatchlist.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeySegmented
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.HeyTextField
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Réglages des serveurs : sources Torznab (Prowlarr ou autre), qBittorrent,
 * catégories et termes exclus. Rien n'est préconfiguré : chaque source est
 * saisie par l'administrateur.
 */
@Composable
fun ServersScreen(viewModel: ServersViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = viewModel.form

    Column(modifier = Modifier.fillMaxSize()) {
        AdminHeader(title = stringResource(R.string.admin_tab_servers))
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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

            item { SectionTitle(stringResource(R.string.admin_sources_title), stringResource(R.string.admin_sources_body)) }
            items(form.sources, key = { it.id }) { source ->
                SourceCard(
                    source = source,
                    test = state.sourceTests[source.id],
                    onChange = { change -> viewModel.editSource(source.id, change) },
                    onTest = { viewModel.testSource(source.id) },
                    onRemove = { viewModel.removeSource(source.id) },
                )
            }
            item {
                HeyButton(
                    text = stringResource(R.string.admin_source_add),
                    onClick = viewModel::addSource,
                    kind = HeyButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { SectionTitle(stringResource(R.string.admin_qbit_title), stringResource(R.string.admin_qbit_body)) }
            item {
                QbitCard(
                    form = form,
                    test = state.qbitTest,
                    onChange = viewModel::edit,
                    onTest = viewModel::testQbittorrent,
                )
            }

            item { SectionTitle(stringResource(R.string.admin_sorting_title), null) }
            item {
                AdminCard {
                    LabeledField(
                        label = stringResource(R.string.admin_movie_category),
                        value = form.movieCategory,
                        onValueChange = { value -> viewModel.edit { it.copy(movieCategory = value) } },
                        placeholder = ServerForm.DEFAULT_MOVIE_CATEGORY,
                    )
                    LabeledField(
                        label = stringResource(R.string.admin_series_category),
                        value = form.seriesCategory,
                        onValueChange = { value -> viewModel.edit { it.copy(seriesCategory = value) } },
                        placeholder = ServerForm.DEFAULT_SERIES_CATEGORY,
                    )
                    LabeledField(
                        label = stringResource(R.string.admin_excluded_terms),
                        value = form.excludedTerms,
                        onValueChange = { value -> viewModel.edit { it.copy(excludedTerms = value) } },
                        placeholder = stringResource(R.string.admin_excluded_terms_placeholder),
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.saveFailure?.let { FailureText(it) }
                    if (state.saved) HeyText(text = stringResource(R.string.admin_servers_saved))
                    HeyButton(
                        text = stringResource(if (state.saving) R.string.admin_servers_saving else R.string.admin_servers_save),
                        onClick = viewModel::save,
                        enabled = !state.saving && state.dirty,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, body: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HeyText(text = title, style = HeyTheme.typography.title)
        body?.let { HeyText(text = it, style = HeyTheme.typography.caption, color = HeyTheme.colors.onBackgroundMuted) }
    }
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
        HeyText(text = label, style = HeyTheme.typography.label, color = HeyTheme.colors.onBackgroundMuted)
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

@Composable
private fun SourceCard(
    source: SourceForm,
    test: ConnectionTest?,
    onChange: ((SourceForm) -> SourceForm) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    AdminCard {
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
        TestResult(test)
        ButtonRow { cell ->
            HeyButton(
                text = stringResource(if (test == ConnectionTest.Running) R.string.admin_testing else R.string.admin_test),
                onClick = onTest,
                kind = HeyButtonKind.Secondary,
                enabled = test != ConnectionTest.Running && source.url.isNotBlank(),
                modifier = cell,
            )
            HeyButton(
                text = stringResource(R.string.admin_remove),
                onClick = onRemove,
                kind = HeyButtonKind.Danger,
                modifier = cell,
            )
        }
    }
}

@Composable
private fun QbitCard(
    form: ServerForm,
    test: ConnectionTest?,
    onChange: ((ServerForm) -> ServerForm) -> Unit,
    onTest: () -> Unit,
) {
    AdminCard {
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
        TestResult(test)
        HeyButton(
            text = stringResource(if (test == ConnectionTest.Running) R.string.admin_testing else R.string.admin_test),
            onClick = onTest,
            kind = HeyButtonKind.Secondary,
            enabled = test != ConnectionTest.Running && form.qbitUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TestResult(test: ConnectionTest?) {
    when (test) {
        null, ConnectionTest.Running -> Unit
        is ConnectionTest.Passed -> HeyText(
            text = if (test.detail.isEmpty()) {
                stringResource(R.string.admin_test_passed)
            } else {
                stringResource(R.string.admin_test_passed_version, test.detail)
            },
            color = HeyTheme.colors.accent,
        )
        is ConnectionTest.SourceFailed -> HeyText(text = stringResource(test.failure.labelRes()), color = HeyTheme.colors.error)
        is ConnectionTest.Failed -> FailureText(test.failure)
    }
}
