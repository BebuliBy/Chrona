package by.bebuliby.chrona.preview

import androidx.lifecycle.ViewModel
import by.bebuliby.chrona.domain.ChronaAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory UI checkpoint. No Room, collector or real user data yet. */
class DemoViewModel : ViewModel() {
    private val mutable = MutableStateFlow(DemoState.sample())
    val state = mutable.asStateFlow()
    fun dispatch(action: ChronaAction) { mutable.update { DemoReducer.reduce(it, action) } }
}
