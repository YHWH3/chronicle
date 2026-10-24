package com.chronicle.app.ui.screens

import android.net.Uri
import android.os.SystemClock
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chronicle.app.BuildConfig
import com.chronicle.app.R
import com.chronicle.app.mind.HfAccess
import com.chronicle.app.mind.SmartModel
import com.chronicle.app.ui.design.*
import com.chronicle.app.ui.vm.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The user accepts the licence in their browser. Chronicle only checks access. */
@Composable
fun HfLicenceDialog(model: SmartModel, viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val auth = viewModel.hfAuth ?: return
    val oauthCompletions by auth.oauthCompletions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var stage by rememberSaveable(model.id) { mutableIntStateOf(0) }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var watchUntil by remember { mutableLongStateOf(0L) }
    var awaitingOAuthAfter by remember { mutableLongStateOf(-1L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun open(url: String) { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
    fun licencePage() {
        stage = 2
        watchUntil = SystemClock.elapsedRealtime() + 180_000
        timedOut = false
        open("https://huggingface.co/${model.repo}")
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(stage, state.hfSignedIn, oauthCompletions) {
        // Only a completed sign-in advances this step. An existing/rejected
        // session must not bounce the access-token alternative back to the licence.
        if (stage == 1 && state.hfSignedIn && awaitingOAuthAfter >= 0 && oauthCompletions > awaitingOAuthAfter) {
            awaitingOAuthAfter = -1
            error = false
            licencePage()
        }
    }
    LaunchedEffect(stage, resumeTick) {
        if (stage != 2) return@LaunchedEffect
        if (watchUntil == 0L) watchUntil = SystemClock.elapsedRealtime() + 180_000
        while (SystemClock.elapsedRealtime() < watchUntil) {
            // Stop polling when the sheet's activity is in the background.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                val access = try { auth.checkAccess(model) }
                catch (ce: CancellationException) { throw ce }
                catch (_: Throwable) { HfAccess.FAILED }
                when (access) {
                    HfAccess.GRANTED -> {
                        viewModel.smartSetup?.download()
                        onDismiss()
                        return@LaunchedEffect
                    }
                    HfAccess.TOKEN_REJECTED -> { error = true; stage = 1; return@LaunchedEffect }
                    HfAccess.FAILED -> error = true
                    HfAccess.WAITING_FOR_LICENCE -> error = false
                }
            }
            delay(5_000)
        }
        timedOut = true
    }
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(level = SurfaceLevel.Elevated, shape = RoundedCornerShape(Radius.md),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.hf_licence_title, model.displayName), style = Chron.type.headline)
                Spacer(Modifier.height(16.dp))
                when (stage) {
                    0 -> {
                        Text(stringResource(R.string.hf_explain), style = Chron.type.body)
                        PrimaryPill(stringResource(R.string.continue_setup), {
                            error = false
                            if (state.hfSignedIn) licencePage() else stage = 1
                        })
                    }
                    1 -> {
                        if (BuildConfig.HF_OAUTH_CLIENT_ID.isNotBlank()) {
                            PrimaryPill(stringResource(R.string.hf_sign_in), {
                                error = false
                                awaitingOAuthAfter = oauthCompletions
                                busy = true
                                scope.launch {
                                    try { open(auth.authorizationUrl()) }
                                    catch (ce: CancellationException) { throw ce }
                                    catch (_: Throwable) { error = true; awaitingOAuthAfter = -1 }
                                    finally { busy = false }
                                }
                            }, loading = busy)
                        }
                        Text(stringResource(R.string.hf_token_alternative), style = Chron.type.controlSmall)
                        Text(stringResource(R.string.hf_token_help), style = Chron.type.bodySmall)
                        GhostButton(stringResource(R.string.hf_open_tokens), {
                            open("https://huggingface.co/settings/tokens")
                        })
                        SecretField(token, { token = it }, stringResource(R.string.hf_token_placeholder))
                        PrimaryPill(stringResource(R.string.hf_use_token), {
                            busy = true
                            scope.launch {
                                try {
                                    if (auth.validateToken(token)) {
                                        token = ""
                                        error = false
                                        licencePage()
                                    } else error = true
                                } catch (ce: CancellationException) { throw ce }
                                catch (_: Throwable) { error = true }
                                finally { busy = false }
                            }
                        }, enabled = token.isNotBlank(), loading = busy)
                    }
                    2 -> {
                        Text(stringResource(if (timedOut) R.string.hf_watch_expired else R.string.hf_waiting),
                            style = Chron.type.body)
                        GhostButton(stringResource(R.string.hf_open_again), { licencePage() })
                        GhostButton(stringResource(R.string.hf_check_again), {
                            watchUntil = SystemClock.elapsedRealtime() + 180_000
                            timedOut = false
                            resumeTick++
                        })
                        GhostButton(stringResource(R.string.hf_token_alternative), { awaitingOAuthAfter = -1; stage = 1 })
                    }
                }
                if (error) Text(stringResource(R.string.hf_setup_error), color = Chron.palette.warning,
                    style = Chron.type.bodySmall)
                GhostButton(stringResource(R.string.cancel_download), onDismiss)
            }
        }
    }
}
