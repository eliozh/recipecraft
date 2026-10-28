package com.elio.recipecraft.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailPaneStoreTest {

    /** One live observation per recipe, like the detail view model's database flow. */
    private val observed = mutableMapOf<String, MutableStateFlow<Int>>()

    private inner class Detail(id: String) : ViewModel() {
        init {
            val flow = observed.getOrPut(id) { MutableStateFlow(0) }
            viewModelScope.launch { flow.collect {} }
        }
    }

    private fun DetailPaneStore.detail(id: String): ViewModel =
        ViewModelProvider.create(
            ownerFor(id),
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: kotlin.reflect.KClass<T>, extras: CreationExtras): T = Detail(id) as T
            },
        )[Detail::class]

    private fun subscribers(id: String) = observed.getValue(id).subscriptionCount.value

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `switching to another recipe stops observing the previous one`() {
        val pane = DetailPaneStore()
        pane.detail("a")
        assertEquals(1, subscribers("a"))

        pane.detail("b")
        assertEquals(0, subscribers("a"))
        assertEquals(1, subscribers("b"))
    }

    @Test
    fun `the open recipe keeps its view model until the pane closes`() {
        val pane = DetailPaneStore()
        val first = pane.detail("a")
        assertSame(first, pane.detail("a"))

        pane.keepOnly("a")
        assertEquals(1, subscribers("a"))
        pane.keepOnly(null)
        assertEquals(0, subscribers("a"))
        assertNotSame(first, pane.detail("a"))
    }

    @Test
    fun `clearing the pane store clears the open recipe`() {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = androidx.lifecycle.ViewModelStore()
        }
        val pane = ViewModelProvider.create(owner)[DetailPaneStore::class]
        pane.detail("a")
        owner.viewModelStore.clear()
        assertEquals(0, subscribers("a"))
    }
}
