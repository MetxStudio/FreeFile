package com.freefile.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI 状态契约：屏幕需渲染的不可变状态。 */
interface UiState

/** UI 意图契约：用户/系统发出的单向意图，由 ViewModel 消费。 */
interface UiIntent

/** UI 副作用契约：一次性事件（导航、Toast、弹窗等），不放入 State */
interface UiEffect

/**
 * MVI ViewModel 基类，实现单向数据流：
 * Intent -> ViewModel.handleIntent -> State / Effect -> UI。
 */
abstract class MviViewModel<S : UiState, I : UiIntent, E : UiEffect> : ViewModel() {

    private val _uiState: MutableStateFlow<S> = MutableStateFlow(initialState())
    val uiState: StateFlow<S> = _uiState.asStateFlow()

    private val _effects: MutableSharedFlow<E> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val effects: SharedFlow<E> = _effects.asSharedFlow()

    protected val intents: MutableSharedFlow<I> = MutableSharedFlow(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        viewModelScope.launch {
            intents.collect(::handleIntent)
        }
    }

    /** 由 UI 层派发意图。 */
    fun dispatch(intent: I) {
        intents.tryEmit(intent)
    }

    /** 由子类提供初始状态。 */
    protected abstract fun initialState(): S

    /** 由子类实现意图处理逻辑。 */
    protected abstract fun handleIntent(intent: I)

    /** 更新状态（在协程内调用）。 */
    protected fun update(reducer: S.() -> S) {
        _uiState.value = _uiState.value.reducer()
    }

    /** 发射一次性副作用。 */
    protected fun emit(effect: E) {
        viewModelScope.launch { _effects.emit(effect) }
    }

    /** 收集外部 Flow 用于驱动状态更新。 */
    protected fun <T> Flow<T>.launchCollect(block: suspend (T) -> Unit) {
        viewModelScope.launch { collect(block) }
    }
}
