package com.futuremind.koru

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


class SuspendWrapper<T : Any>(
    private val scopeProvider: ScopeProvider?,
    private val freezeWrapper: Boolean,
    private val suspender: suspend () -> T
) {

    init {
        if (freezeWrapper) this.freeze()
    }

    fun subscribe(
        onSuccess: (item: T) -> Unit,
        onThrow: (error: Throwable) -> Unit
    ) = subscribe(
        scope = scopeProvider?.scope
            ?: throw IllegalArgumentException("To use implicit scope, you have to provide it via @ToNativeClass.launchOnScope and @ExportedScopeProvider."),
        onSuccess = onSuccess,
        onThrow = onThrow
    )

    fun subscribe(
        scope: CoroutineScope,
        onSuccess: (item: T) -> Unit,
        onThrow: (error: Throwable) -> Unit
    ): Job = scope
        .launch {
            try {
                onSuccess(suspender().freeze())
            } catch (error: Throwable) {
                onThrow(error.freeze())
            }
        }
        .apply { if (freezeWrapper) this.freeze() }

}