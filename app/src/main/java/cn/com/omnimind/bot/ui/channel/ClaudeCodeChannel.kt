package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.bot.claude.ClaudeCodeAppServerManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClaudeCodeChannel {
    companion object {
        private const val METHOD_CHANNEL = "cn.com.omnimind.bot/ClaudeCode"
        private const val EVENT_CHANNEL = "cn.com.omnimind.bot/ClaudeCodeEvents"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var context: Context? = null
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null

    fun onCreate(context: Context) {
        this.context = context.applicationContext
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
        methodChannel?.setMethodCallHandler(::handleMethodCall)

        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        eventChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }
            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val safeContext = context
        if (safeContext == null) {
            result.error("CLAUDE_CONTEXT_ERROR", "Context not initialized", null)
            return
        }
        val arguments = (call.arguments as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to value }
            .orEmpty()

        scope.launch {
            runCatching {
                ClaudeCodeAppServerManager
                    .getInstance(safeContext)
                    .handleMethod(call.method, arguments, eventSink)
            }.onSuccess { payload ->
                result.success(payload)
            }.onFailure { error ->
                result.error(
                    "CLAUDE_CODE_CALL_FAILED",
                    error.message ?: error.javaClass.simpleName,
                    null
                )
            }
        }
    }

    fun clear() {
        eventSink = null
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        eventChannel?.setStreamHandler(null)
        eventChannel = null
    }
}
