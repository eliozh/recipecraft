package com.elio.recipecraft.ui.cover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.domain.Recipe
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CookUiState(
    /** True until the recipe is first read. */
    val loading: Boolean = true,
    val recipe: Recipe? = null,
    val notFound: Boolean = false,
    val loadFailed: Boolean = false,
    /** The position of the step on screen, from 0. */
    val page: Int = 0,
    /** The timers the user has touched, by step position; the others are idle. */
    val timers: Map<Int, CookTimer> = emptyMap(),
    /** The [ElapsedClock] time the timers are shown at. */
    val now: Long = 0,
) {
    val stepCount: Int get() = recipe?.steps?.size ?: 0

    /** The timer of the step at [position], or null when that step gives no minutes. */
    fun timer(position: Int): CookTimer? =
        timers[position] ?: recipe?.steps?.getOrNull(position)?.minutes?.takeIf { it > 0 }?.let { CookTimer.ofMinutes(it) }
}

/**
 * Cook mode: one step of a saved recipe at a time, with a countdown for each step that gives
 * minutes. The step and the timers are kept in the [SavedStateHandle], and the timers count on an
 * [ElapsedClock], so folding, unfolding and even a process restart keep them where they were.
 */
@HiltViewModel
class CookModeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository,
    private val clock: ElapsedClock,
) : ViewModel() {

    private val recipeId: String? = savedStateHandle[ARG_RECIPE_ID]

    private val _uiState = MutableStateFlow(
        CookUiState(
            page = savedStateHandle[KEY_PAGE] ?: 0,
            timers = savedStateHandle.get<LongArray>(KEY_TIMERS)?.toCookTimers().orEmpty(),
            now = clock.millis(),
        ),
    )
    val uiState: StateFlow<CookUiState> = _uiState.asStateFlow()

    /** A timer ran out; the screen gives a short signal. */
    private val _finished = Channel<Int>(Channel.BUFFERED)
    val finished: Flow<Int> = _finished.receiveAsFlow()

    private var ticker: Job? = null

    /**
     * The version of the recipe the timers belong to: its `updatedAt` and step count. Timers are
     * kept by step position, so once the recipe changes they would count for the wrong steps.
     */
    private var timersFor: LongArray? = savedStateHandle[KEY_TIMERS_FOR]

    init {
        if (recipeId == null) {
            _uiState.update { it.copy(loading = false, notFound = true) }
        } else {
            viewModelScope.launch { observe(recipeId) }
        }
        tickWhileRunning()
    }

    fun onPageChanged(page: Int) {
        if (page == _uiState.value.page) return
        _uiState.update { it.copy(page = page) }
        savedStateHandle[KEY_PAGE] = page
    }

    /** Starts or resumes the timer of the step at [position]. */
    fun startTimer(position: Int) {
        changeTimer(position) { start(clock.millis()) }
        tickWhileRunning()
    }

    fun pauseTimer(position: Int) = changeTimer(position) { pause(clock.millis()) }

    fun resetTimer(position: Int) = changeTimer(position) { reset() }

    private fun changeTimer(position: Int, change: CookTimer.() -> CookTimer) {
        val timer = _uiState.value.timer(position) ?: return
        _uiState.update { it.copy(timers = it.timers + (position to timer.change()), now = clock.millis()) }
        saveTimers()
    }

    /** Moves the clock on screen while a timer runs, and stops each one whose time is up. */
    private fun tickWhileRunning() {
        if (ticker?.isActive == true || _uiState.value.timers.values.none { it.isRunning }) return
        ticker = viewModelScope.launch {
            while (true) {
                val now = clock.millis()
                val before = _uiState.value.timers
                val after = before.mapValues { (_, timer) -> timer.tick(now) }
                _uiState.update { it.copy(timers = after, now = now) }
                val finished = after.filter { (position, timer) -> !timer.isRunning && before[position]?.isRunning == true }
                if (finished.isNotEmpty()) {
                    saveTimers()
                    finished.keys.forEach { _finished.send(it) }
                }
                if (after.values.none { it.isRunning }) break
                delay(TICK_MILLIS)
            }
        }
    }

    private fun saveTimers() {
        savedStateHandle[KEY_TIMERS] = _uiState.value.timers.toLongArray()
        savedStateHandle[KEY_TIMERS_FOR] = timersFor
    }

    /**
     * Ties the timers to [recipe]: timers set for another version of it, such as before it was
     * translated or replaced, are dropped.
     */
    private fun keepTimersFor(recipe: Recipe) {
        val version = longArrayOf(recipe.updatedAt, recipe.steps.size.toLong())
        if (timersFor?.contentEquals(version) == true) return
        val hadTimers = _uiState.value.timers.isNotEmpty()
        timersFor = version
        if (hadTimers) _uiState.update { it.copy(timers = emptyMap()) }
        saveTimers()
    }

    private suspend fun observe(id: String) {
        repository.observeDetail(id)
            .catch { e ->
                if (e !is RuntimeException || e is CancellationException) throw e
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
            .collect { detail ->
                detail?.let { keepTimersFor(it.recipe) }
                _uiState.update { state ->
                    if (detail == null) {
                        state.copy(loading = false, recipe = null, notFound = true)
                    } else {
                        val steps = detail.recipe.steps.size
                        state.copy(
                            loading = false,
                            recipe = detail.recipe,
                            notFound = false,
                            loadFailed = false,
                            page = state.page.coerceIn(0, (steps - 1).coerceAtLeast(0)),
                        )
                    }
                }
            }
    }

    companion object {
        /** The navigation argument that carries the recipe id. */
        const val ARG_RECIPE_ID = "recipeId"
        private const val KEY_PAGE = "cook_page"
        private const val KEY_TIMERS = "cook_timers"
        private const val KEY_TIMERS_FOR = "cook_timers_for"

        /** Often enough that the seconds on screen never lag by a visible step. */
        internal const val TICK_MILLIS = 250L
    }
}
