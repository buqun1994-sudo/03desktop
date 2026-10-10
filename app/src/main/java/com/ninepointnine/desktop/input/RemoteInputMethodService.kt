package com.ninepointnine.desktop.input

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.util.TypedValue
import android.os.Handler
import android.os.Looper

/** A tiny bundled IME: it only commits one pending transaction to a valid editor. */
class RemoteInputMethodService : InputMethodService() {
    private val handler = Handler(Looper.getMainLooper())
    private val listenerKey = Any()
    private lateinit var coordinator: RemoteInputCoordinator

    override fun onCreate() {
        super.onCreate()
        coordinator = RemoteInputRuntime.coordinator(this)
        coordinator.listen(listenerKey) { state ->
            if (state.sending) handler.post(::tryCommit)
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        scheduleBoundedAttempts()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        handler.post(::tryCommit)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        handler.post(::tryCommit)
    }

    override fun onFinishInput() {
        handler.removeCallbacksAndMessages(null)
        super.onFinishInput()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::coordinator.isInitialized) {
            coordinator.unlisten(listenerKey)
            coordinator.imeLost()
        }
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View = TextView(this).apply {
        text = getString(com.ninepointnine.desktop.R.string.remote_input_hint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(10, 4, 10, 4)
        isSingleLine = true
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun scheduleBoundedAttempts() {
        handler.removeCallbacks(::tryCommit)
        handler.post(::tryCommit)
        handler.postDelayed(::tryCommit, 100L)
        handler.postDelayed(::tryCommit, 500L)
        handler.postDelayed(::tryCommit, 2_000L)
    }

    private fun tryCommit() {
        if (!::coordinator.isInitialized || !coordinator.state().sending) return
        val connection = currentInputConnection ?: return
        val editor = currentInputEditorInfo ?: return
        val packageName = editor.packageName?.toString().orEmpty()
        val validEditor = editor.inputType != InputType.TYPE_NULL && packageName.isNotBlank()
        coordinator.tryCommit(
            editorPackage = packageName,
            validEditor = validEditor,
            sessionIsCurrent = {
                currentInputConnection === connection &&
                    currentInputEditorInfo?.packageName?.toString() == packageName
            },
            currentFocus = RemoteInputRuntime::currentFocus,
            commit = { text -> connection.commitText(text, 1) },
        )
    }
}
