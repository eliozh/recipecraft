package com.elio.recipecraft.ui.cover

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.elio.recipecraft.data.recipe.RecipeRepository
import com.elio.recipecraft.domain.Step
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.testRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CookModeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val dao = FakeRecipeDao()
    private val repository = RecipeRepository(dao, FakeNutritionLookups(), UnconfinedTestDispatcher(dispatcher.scheduler))

    /** The test scheduler's virtual time, so delays and the timers move together. */
    private val clock = ElapsedClock { dispatcher.scheduler.currentTime }

    private val recipe = testRecipe("soup").copy(
        steps = listOf(Step(1, "Chop", null), Step(2, "Simmer", 2), Step(3, "Rest", 1)),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle(mapOf(CookModeViewModel.ARG_RECIPE_ID to recipe.id))) =
        CookModeViewModel(handle, repository, clock)

    @Test
    fun loadsTheRecipeAndOffersATimerOnlyForStepsWithMinutes() = scope.runTest {
        repository.save(listOf(recipe))
        val vm = viewModel()
        runCurrent()
        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(recipe.id, state.recipe?.id)
        assertEquals(3, state.stepCount)
        assertEquals(null, state.timer(0))
        assertEquals(CookTimer.ofMinutes(2), state.timer(1))
        assertEquals(null, state.timer(7))
    }

    @Test
    fun aMissingRecipeOrIdIsNotFound() = scope.runTest {
        val missing = viewModel()
        val noId = CookModeViewModel(SavedStateHandle(), repository, clock)
        runCurrent()
        assertTrue(missing.uiState.value.notFound)
        assertTrue(noId.uiState.value.notFound)
        assertFalse(noId.uiState.value.loading)
    }

    @Test
    fun theTimerCountsDownAndFinishesOnce() = scope.runTest {
        repository.save(listOf(recipe))
        val vm = viewModel()
        runCurrent()
        vm.finished.test {
            vm.startTimer(2)
            runCurrent()
            assertEquals(CookTimerStatus.RUNNING, vm.uiState.value.timers.getValue(2).status(vm.uiState.value.now))

            advanceTimeBy(30_000)
            runCurrent()
            val state = vm.uiState.value
            assertEquals(30_000, state.timer(2)!!.remaining(state.now))

            vm.pauseTimer(2)
            advanceTimeBy(100_000)
            runCurrent()
            assertEquals(CookTimerStatus.PAUSED, vm.uiState.value.timer(2)!!.status(vm.uiState.value.now))
            assertEquals(30_000, vm.uiState.value.timer(2)!!.remaining(vm.uiState.value.now))

            vm.startTimer(2)
            advanceTimeBy(30_000 + CookModeViewModel.TICK_MILLIS)
            runCurrent()
            assertEquals(2, awaitItem())
            val done = vm.uiState.value
            assertEquals(CookTimerStatus.FINISHED, done.timer(2)!!.status(done.now))
            advanceTimeBy(60_000)
            expectNoEvents()

            vm.resetTimer(2)
            assertEquals(CookTimer.ofMinutes(1), vm.uiState.value.timer(2))
        }
    }

    @Test
    fun aStepWithoutMinutesHasNoTimerToStart() = scope.runTest {
        repository.save(listOf(recipe))
        val vm = viewModel()
        runCurrent()
        vm.startTimer(0)
        assertTrue(vm.uiState.value.timers.isEmpty())
    }

    @Test
    fun theStepAndTimersComeBackFromTheSavedState() = scope.runTest {
        repository.save(listOf(recipe))
        val handle = SavedStateHandle(mapOf(CookModeViewModel.ARG_RECIPE_ID to recipe.id))
        val first = viewModel(handle)
        runCurrent()
        first.onPageChanged(1)
        first.startTimer(1)
        advanceTimeBy(20_000)
        runCurrent()

        // A new view model on the same saved state, as after the process was let go.
        val second = viewModel(handle)
        runCurrent()
        val state = second.uiState.value
        assertEquals(1, state.page)
        assertEquals(CookTimerStatus.RUNNING, state.timer(1)!!.status(state.now))
        assertEquals(100_000, state.timer(1)!!.remaining(state.now))
        // It keeps ticking on its own.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(90_000, second.uiState.value.let { it.timer(1)!!.remaining(it.now) }, 250.0)
    }

    @Test
    fun aSavedPageBeyondTheStepsIsClampedToTheLastStep() = scope.runTest {
        repository.save(listOf(recipe))
        val vm = viewModel(SavedStateHandle(mapOf(CookModeViewModel.ARG_RECIPE_ID to recipe.id, "cook_page" to 9)))
        runCurrent()
        assertEquals(2, vm.uiState.value.page)
    }

    @Test
    fun timersOfAnotherVersionOfTheRecipeAreDroppedOnRestore() = scope.runTest {
        repository.save(listOf(recipe))
        val handle = SavedStateHandle(mapOf(CookModeViewModel.ARG_RECIPE_ID to recipe.id))
        val first = viewModel(handle)
        runCurrent()
        first.startTimer(1)
        runCurrent()

        // The recipe was replaced while the process was gone: a new step comes first.
        val replaced = recipe.copy(
            steps = listOf(Step(1, "Boil water", 5)) + recipe.steps.map { it.copy(index = it.index + 1) },
            updatedAt = recipe.updatedAt + 1,
        )
        repository.update(replaced)
        val second = viewModel(handle)
        runCurrent()
        val state = second.uiState.value
        assertTrue(state.timers.isEmpty())
        assertEquals(CookTimer.ofMinutes(2), state.timer(2))
        assertEquals(CookTimer.ofMinutes(5), state.timer(0))
    }

    @Test
    fun timersAreDroppedWhenTheRecipeChangesWhileCookModeIsOpen() = scope.runTest {
        repository.save(listOf(recipe))
        val vm = viewModel()
        runCurrent()
        vm.startTimer(1)
        runCurrent()

        repository.update(recipe.copy(name = "Soupe", updatedAt = recipe.updatedAt + 1))
        runCurrent()
        assertTrue(vm.uiState.value.timers.isEmpty())

        // Timers started on the new version are kept as it updates again with nothing changed.
        vm.startTimer(1)
        repository.update(recipe.copy(name = "Soupe", updatedAt = recipe.updatedAt + 1))
        runCurrent()
        assertEquals(setOf(1), vm.uiState.value.timers.keys)
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Double) =
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta)
}
