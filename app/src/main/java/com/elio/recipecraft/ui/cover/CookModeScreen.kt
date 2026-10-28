package com.elio.recipecraft.ui.cover

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.recipecraft.R
import com.elio.recipecraft.ui.common.AppError
import com.elio.recipecraft.domain.Step
import com.elio.recipecraft.ui.LocalAppLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cook mode: a saved recipe one step at a time, in large type, with the screen kept on while it is
 * open. Swipe or use Previous and Next, both at the bottom within reach of a thumb. A step that
 * gives minutes has a countdown; when it runs out the timer turns to "Time's up" with a short
 * haptic and a beep, neither of which needs a permission.
 */
@Composable
fun CookModeScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CookModeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    KeepScreenOn()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(viewModel) {
        viewModel.finished.collect {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            beep()
        }
    }
    CookModeContent(
        state = state,
        onClose = onClose,
        onPageChanged = viewModel::onPageChanged,
        onStartTimer = viewModel::startTimer,
        onPauseTimer = viewModel::pauseTimer,
        onResetTimer = viewModel::resetTimer,
        modifier = modifier,
    )
}

/** Cook mode for [state], without the view model. */
@Composable
fun CookModeContent(
    state: CookUiState,
    onClose: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onStartTimer: (Int) -> Unit,
    onPauseTimer: (Int) -> Unit,
    onResetTimer: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = state.recipe?.steps.orEmpty()
    Column(modifier.fillMaxSize().testTag(CookTestTags.SCREEN)) {
        when {
            state.loading -> CookTopBar(title = "", onClose = onClose)
            state.recipe == null -> {
                CookTopBar(title = "", onClose = onClose)
                Message(stringResource(if (state.loadFailed) AppError.RECIPE_LOAD_FAILED.message else AppError.RECIPE_NOT_FOUND.message))
            }
            steps.isEmpty() -> {
                CookTopBar(title = state.recipe.name, onClose = onClose)
                Message(stringResource(R.string.cook_no_steps))
            }
            else -> Steps(state, steps, onClose, onPageChanged, onStartTimer, onPauseTimer, onResetTimer)
        }
    }
}

@Composable
private fun ColumnScope.Steps(
    state: CookUiState,
    steps: List<Step>,
    onClose: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onStartTimer: (Int) -> Unit,
    onPauseTimer: (Int) -> Unit,
    onResetTimer: (Int) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = state.page.coerceIn(0, steps.lastIndex)) { steps.size }
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { currentOnPageChanged(it) }
    }
    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage
    val cover = LocalAppLayout.current.cover

    val indicator = stringResource(R.string.cook_step_indicator, page + 1, steps.size)
    val indicatorDescription = stringResource(R.string.cook_step_of, page + 1, steps.size)
    CookTopBar(title = state.recipe?.name.orEmpty(), onClose = onClose) {
        Text(
            indicator,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .semantics {
                    contentDescription = indicatorDescription
                    liveRegion = LiveRegionMode.Polite
                }
                .testTag(CookTestTags.INDICATOR),
        )
    }
    HorizontalPager(
        state = pagerState,
        key = { it },
        modifier = Modifier.weight(1f).fillMaxWidth().testTag(CookTestTags.PAGER),
    ) { position ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    steps[position].text,
                    style = if (cover) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(vertical = 8.dp).testTag(CookTestTags.STEP_TEXT),
                )
            }
            state.timer(position)?.let { timer ->
                StepTimer(
                    timer = timer,
                    now = state.now,
                    onStart = { onStartTimer(position) },
                    onPause = { onPauseTimer(position) },
                    onReset = { onResetTimer(position) },
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedButton(
            onClick = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
            enabled = page > 0,
            modifier = Modifier.weight(1f).heightIn(min = COOK_BUTTON_HEIGHT).testTag(CookTestTags.PREVIOUS),
        ) {
            Text(stringResource(R.string.cook_previous), maxLines = 1)
        }
        val last = page == steps.lastIndex
        Button(
            onClick = { if (last) onClose() else scope.launch { pagerState.animateScrollToPage(page + 1) } },
            modifier = Modifier.weight(1f).heightIn(min = COOK_BUTTON_HEIGHT).testTag(if (last) CookTestTags.DONE else CookTestTags.NEXT),
        ) {
            Text(stringResource(if (last) R.string.cook_done else R.string.cook_next), maxLines = 1)
        }
    }
}

@Composable
private fun CookTopBar(title: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}) {
    CoverTopBar(
        title = title,
        navigationIcon = {
            IconButton(onClick = onClose, modifier = Modifier.testTag(CookTestTags.CLOSE)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cook_close))
            }
        },
        actions = { actions() },
    )
}

/** The countdown of one step: the time left, Start or Pause, and Reset. */
@Composable
private fun StepTimer(
    timer: CookTimer,
    now: Long,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = timer.status(now)
    val finished = status == CookTimerStatus.FINISHED
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (finished) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(CookTestTags.TIMER),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatCountdown(timer.remaining(now)),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag(CookTestTags.TIMER_TIME),
                )
                if (finished) {
                    Text(
                        stringResource(R.string.cook_timer_finished),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .semantics { liveRegion = LiveRegionMode.Assertive }
                            .testTag(CookTestTags.TIMER_FINISHED),
                    )
                }
            }
            if (status == CookTimerStatus.RUNNING) {
                FilledIconButton(onClick = onPause, modifier = Modifier.testTag(CookTestTags.TIMER_PAUSE)) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = stringResource(R.string.cook_timer_pause))
                }
            } else {
                val label = when (status) {
                    CookTimerStatus.PAUSED -> R.string.cook_timer_resume
                    CookTimerStatus.FINISHED -> R.string.cook_timer_restart
                    else -> R.string.cook_timer_start
                }
                FilledIconButton(onClick = onStart, modifier = Modifier.testTag(CookTestTags.TIMER_START)) {
                    Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(label))
                }
            }
            IconButton(
                onClick = onReset,
                enabled = status != CookTimerStatus.IDLE,
                modifier = Modifier.testTag(CookTestTags.TIMER_RESET),
            ) {
                Icon(painterResource(R.drawable.ic_replay), contentDescription = stringResource(R.string.cook_timer_reset))
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(CookTestTags.MESSAGE),
        )
    }
}

/** Keeps the screen on while this is in the composition; no wake lock, so no permission. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** A short beep on the notification stream, which follows the user's volume and Do Not Disturb. */
private suspend fun beep() {
    val tone = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME)
    } catch (_: RuntimeException) {
        return // No audio route for tones on this device; the haptic and the screen still say it.
    }
    try {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_MILLIS)
        delay(BEEP_MILLIS.toLong())
    } finally {
        tone.release()
    }
}

private val COOK_BUTTON_HEIGHT = 56.dp
private const val BEEP_VOLUME = 80
private const val BEEP_MILLIS = 400

/** Stable handles for UI tests. */
object CookTestTags {
    const val SCREEN = "cook"
    const val CLOSE = "cook_close"
    const val INDICATOR = "cook_indicator"
    const val PAGER = "cook_pager"
    const val STEP_TEXT = "cook_step_text"
    const val PREVIOUS = "cook_previous"
    const val NEXT = "cook_next"
    const val DONE = "cook_done"
    const val TIMER = "cook_timer"
    const val TIMER_TIME = "cook_timer_time"
    const val TIMER_FINISHED = "cook_timer_finished"
    const val TIMER_START = "cook_timer_start"
    const val TIMER_PAUSE = "cook_timer_pause"
    const val TIMER_RESET = "cook_timer_reset"
    const val MESSAGE = "cook_message"
}
