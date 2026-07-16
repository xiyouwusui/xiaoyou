package cn.com.omnimind.uikit.loader.cat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.R
import cn.com.omnimind.uikit.UIKit
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

class DraggableBallLoader(
    private val context: Context
) {
    companion object {
        private const val TAG = "PetOverlay"
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageResource(R.mipmap.ic_cat_normal)
    }
    private val messageView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(0xD9222222.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        visibility = View.GONE
    }
    private val container = FrameLayout(context).apply {
        addView(
            messageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply {
                rightMargin = dp(64)
            }
        )
        addView(
            imageView,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.CENTER_VERTICAL)
        )
    }
    private val params = WindowManager.LayoutParams(
        dp(280),
        dp(72),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = context.resources.displayMetrics.widthPixels - width
        y = context.resources.displayMetrics.heightPixels / 2
    }

    var isAttachedToWindow: Boolean = false
        private set
    private var hiddenForExternalActivity = false
    private var completionAction: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    init {
        refreshPetAppearance()
        imageView.setOnClickListener {
            completionAction?.also {
                completionAction = null
                messageView.visibility = View.GONE
            }?.invoke() ?: scope.launch {
                UIKit.uiChatEvent?.showChatBotHalfScreen()
            }
        }
        installDragHandler()
    }

    fun loadBall() {
        if (isAttachedToWindow) return
        runCatching {
            windowManager.addView(container, params)
            isAttachedToWindow = true
            hiddenForExternalActivity = false
        }.onFailure { OmniLog.e(TAG, "Unable to show pet overlay", it) }
    }

    fun collapseNotChangeState() {
        messageView.visibility = View.GONE
        completionAction = null
    }

    fun collapse() = collapseNotChangeState()

    fun message(message: String, onClick: (() -> Unit)? = null): Boolean {
        if (!isAttachedToWindow) return false
        messageView.text = message
        messageView.visibility = View.VISIBLE
        completionAction = onClick
        bringToFront()
        return true
    }

    fun refreshPetAppearance() {
        val prefs = context.getSharedPreferences("OmnibotSettings", Context.MODE_PRIVATE)
        val path = prefs.getString("pet_overlay_image_path", "")?.trim().orEmpty()
        val selectedId = prefs.getString("pet_overlay_selected_id", "builtin:xiaowan").orEmpty()
        if (selectedId == "builtin:xiaowan" || path.isBlank() || path == "__builtin_xiaowan__") {
            imageView.setImageResource(R.mipmap.ic_cat_normal)
            return
        }
        val file = File(path)
        if (file.isFile) {
            Glide.with(imageView).load(file).centerCrop().into(imageView)
        } else {
            imageView.setImageResource(R.mipmap.ic_cat_normal)
        }
    }

    fun destroy() {
        if (isAttachedToWindow) {
            runCatching { windowManager.removeView(container) }
        }
        isAttachedToWindow = false
        hiddenForExternalActivity = false
        completionAction = null
    }

    fun hideForExternalActivity(): Boolean {
        if (!isAttachedToWindow) return false
        return runCatching {
            windowManager.removeView(container)
            isAttachedToWindow = false
            hiddenForExternalActivity = true
            true
        }.getOrElse {
            OmniLog.e(TAG, "Unable to hide pet overlay", it)
            false
        }
    }

    fun restoreAfterExternalActivity(): Boolean {
        if (!hiddenForExternalActivity || isAttachedToWindow) return false
        return runCatching {
            windowManager.addView(container, params)
            isAttachedToWindow = true
            hiddenForExternalActivity = false
            true
        }.getOrElse {
            OmniLog.e(TAG, "Unable to restore pet overlay", it)
            false
        }
    }

    private fun bringToFront() {
        if (isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(container, params) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDragHandler() {
        imageView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    bringToFront()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dragged = abs(event.rawX - downX) > dp(6) ||
                        abs(event.rawY - downY) > dp(6)
                    if (!dragged) imageView.performClick()
                    dragged
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
