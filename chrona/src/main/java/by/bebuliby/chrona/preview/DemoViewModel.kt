package by.bebuliby.chrona.preview

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory UI checkpoint. No Room, collector or real user data yet. */
class DemoViewModel : ViewModel() {
    private val mutable = MutableStateFlow(DemoState.sample())
    val state = mutable.asStateFlow()
    fun dispatch(action: DemoAction) { mutable.update { DemoReducer.reduce(it, action) } }
}
