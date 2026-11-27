package app.heywatchlist.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyPullToRefresh
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.HeyTextField
import app.heywatchlist.ui.theme.HeyTheme
import uniffi.heywatchlist_core.AccessStatus
import uniffi.heywatchlist_core.AllowedEmail
import uniffi.heywatchlist_core.Member

/**
 * Accès : le lien de l'app sert d'invitation, chaque nouveau compte attend
 * ici d'être accepté ou refusé en un geste. Plus bas, les membres (accès
 * retirable) et les adresses acceptées d'avance.
 */
@Composable
fun AccessScreen(viewModel: AccessViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = state.members.filter { it.access == AccessStatus.PENDING }
    val others = state.members.filter { it.access != AccessStatus.PENDING }
    Column(modifier = Modifier.fillMaxSize()) {
        AdminHeader(title = stringResource(R.string.admin_tab_access))
        HeyPullToRefresh(
            refreshing = state.loading && state.members.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.actionFailure?.let { failure -> item { AdminCard { FailureText(failure) } } }
                val failure = state.failure
                when {
                    failure != null -> item { FailurePanel(failure, onRetry = viewModel::refresh) }
                    state.loading && state.members.isEmpty() -> item { LoadingRow(stringResource(R.string.status_loading)) }
                }
                item { SectionTitle(stringResource(R.string.admin_access_pending_title)) }
                if (pending.isEmpty() && !state.loading) {
                    item { EmptyText(stringResource(R.string.admin_access_pending_none)) }
                }
                items(pending, key = { it.id }) { member ->
                    PendingRow(
                        member = member,
                        busy = member.id in state.busy,
                        onApprove = { viewModel.decide(member, granted = true) },
                        onRefuse = { viewModel.decide(member, granted = false) },
                    )
                }
                item { SectionTitle(stringResource(R.string.admin_access_members_title)) }
                items(others, key = { it.id }) { member ->
                    MemberRow(
                        member = member,
                        busy = member.id in state.busy,
                        onRemove = { removing = member.id },
                        onRestore = { viewModel.decide(member, granted = true) },
                    )
                }
                item {
                    AllowEmailCard(
                        adding = state.adding,
                        approved = state.approvedByEmail,
                        onAdd = viewModel::addEmail,
                    )
                }
                items(state.emails, key = { "email-${it.email}" }) { email ->
                    EmailRow(email = email, busy = email.email in state.busy, onRemove = { viewModel.removeEmail(email) })
                }
            }
        }
    }
    val target = removing?.let { id -> state.members.firstOrNull { it.id == id } }
    if (target != null) {
        AdminDialog(
            title = stringResource(R.string.admin_access_remove_title, displayName(target)),
            body = stringResource(R.string.admin_access_remove_body),
            onDismiss = { removing = null },
        ) {
            HeyButton(
                text = stringResource(R.string.admin_access_remove_confirm),
                kind = HeyButtonKind.Danger,
                onClick = {
                    viewModel.decide(target, granted = false)
                    removing = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            HeyButton(
                text = stringResource(R.string.admin_cancel),
                kind = HeyButtonKind.Secondary,
                onClick = { removing = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    HeyText(text = text, style = HeyTheme.typography.title)
}

@Composable
private fun displayName(member: Member): String =
    member.displayName ?: member.email ?: stringResource(R.string.admin_access_unnamed)

/** Prénom, adresse et date : de quoi reconnaître la personne. */
@Composable
private fun Identity(member: Member, dateRes: Int, date: Long) {
    HeyText(text = displayName(member))
    member.email?.takeIf { member.displayName != null }?.let {
        HeyText(text = it, style = HeyTheme.typography.caption, color = HeyTheme.colors.onBackgroundMuted)
    }
    HeyText(
        text = stringResource(dateRes, formatDate(date)),
        style = HeyTheme.typography.caption,
        color = HeyTheme.colors.onBackgroundMuted,
    )
}

@Composable
private fun PendingRow(member: Member, busy: Boolean, onApprove: () -> Unit, onRefuse: () -> Unit) {
    AdminCard {
        Identity(member, R.string.admin_access_requested_on, member.createdAt)
        ButtonRow { modifier ->
            HeyButton(
                text = stringResource(R.string.admin_access_approve),
                onClick = onApprove,
                enabled = !busy,
                modifier = modifier,
            )
            HeyButton(
                text = stringResource(R.string.admin_access_refuse),
                onClick = onRefuse,
                kind = HeyButtonKind.Danger,
                enabled = !busy,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun MemberRow(member: Member, busy: Boolean, onRemove: () -> Unit, onRestore: () -> Unit) {
    AdminCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val refused = member.access == AccessStatus.REFUSED
                Identity(
                    member,
                    if (refused) R.string.admin_access_refused_on else R.string.admin_access_member_since,
                    member.statusChangedAt ?: member.createdAt,
                )
                if (member.isAdmin) {
                    HeyText(text = stringResource(R.string.admin_access_is_admin), color = HeyTheme.colors.onBackgroundMuted)
                }
            }
            when {
                member.isAdmin -> Unit
                member.access == AccessStatus.REFUSED -> HeyButton(
                    text = stringResource(R.string.admin_access_restore),
                    onClick = onRestore,
                    kind = HeyButtonKind.Secondary,
                    enabled = !busy,
                )
                else -> HeyButton(
                    text = stringResource(R.string.admin_access_remove),
                    onClick = onRemove,
                    kind = HeyButtonKind.Danger,
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
private fun AllowEmailCard(adding: Boolean, approved: Int?, onAdd: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (email.isNotBlank() && !adding) {
            onAdd(email)
            email = ""
        }
    }
    AdminCard {
        HeyText(text = stringResource(R.string.admin_access_emails_title), style = HeyTheme.typography.title)
        HeyText(text = stringResource(R.string.admin_access_emails_body), color = HeyTheme.colors.onBackgroundMuted)
        HeyTextField(
            value = email,
            onValueChange = { email = it.take(254) },
            placeholder = stringResource(R.string.admin_access_email_placeholder),
            label = stringResource(R.string.admin_access_email_label),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        HeyButton(
            text = stringResource(if (adding) R.string.admin_access_email_adding else R.string.admin_access_email_add),
            onClick = submit,
            enabled = !adding && email.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (approved != null && approved > 0) {
            HeyText(
                text = pluralStringResource(R.plurals.admin_access_email_approved, approved, approved),
                color = HeyTheme.colors.onBackgroundMuted,
            )
        }
    }
}

@Composable
private fun EmailRow(email: AllowedEmail, busy: Boolean, onRemove: () -> Unit) {
    AdminCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HeyText(text = email.email)
                HeyText(
                    text = stringResource(R.string.admin_access_email_since, formatDate(email.createdAt)),
                    style = HeyTheme.typography.caption,
                    color = HeyTheme.colors.onBackgroundMuted,
                )
            }
            HeyButton(
                text = stringResource(R.string.admin_access_email_remove),
                onClick = onRemove,
                kind = HeyButtonKind.Secondary,
                enabled = !busy,
            )
        }
    }
}
