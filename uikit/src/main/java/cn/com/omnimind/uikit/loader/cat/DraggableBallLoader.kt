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
import com.bumptech.glide.Glide
import java.io.File
import kotlin.math.abs

class DraggableBallLoader(
    private val context: Context
) {
    companion object {
        private const val TAG = "PetOverlay"
        private const val PET_SIZE_DP = 56
        private const val HINT_WIDTH_DP = 280
        private const val HINT_GAP_DP = 8
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayWindowType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    private val petSizePx = dp(PET_SIZE_DP)
    private val hintMaxWidthPx = dp(HINT_WIDTH_DP - PET_SIZE_DP - HINT_GAP_DP)
    private val hintGapPx = dp(HINT_GAP_DP)
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
            imageView,
            FrameLayout.LayoutParams(
                petSizePx,
                petSizePx,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        )
    }
    private val params = WindowManager.LayoutParams(
        petSizePx,
        petSizePx,
        overlayWindowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = context.resources.displayMetrics.widthPixels - width
        y = context.resources.displayMetrics.heightPixels / 2
    }
    private val messageParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayWindowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    var isAttachedToWindow: Boolean = false
        private set
    private var isMessageAttachedToWindow = false
    private var hiddenForExternalActivity = false
    private var messageHiddenForExternalActivity = false
    private var completionAction: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    init {
        refreshPetAppearance()
        imageView.setOnClickListener {
            val action = completionAction ?: return@setOnClickListener
            collapseNotChangeState()
            action()
        }
        installDragHandler()
    }

    fun loadBall() {
        if (isAttachedToWindow) return
        collapseNotChangeState()
        runCatching {
            windowManager.addView(container, params)
            isAttachedToWindow = true
            hiddenForExternalActivity = false
        }.onFailure { OmniLog.e(TAG, "Unable to show pet overlay", it) }
    }

    fun collapseNotChangeState() {
        completionAction = null
        hideMessage()
    }

    fun collapse() = collapseNotChangeState()

    fun message(message: String, onClick: (() -> Unit)? = null): Boolean {
        if (!isAttachedToWindow) return false
        messageView.text = message
        messageView.visibility = View.VISIBLE
        completionAction = onClick
        return showOrUpdateMessage()
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
        collapseNotChangeState()
        if (isAttachedToWindow) {
            runCatching { windowManager.removeView(container) }
        }
        isAttachedToWindow = false
        hiddenForExternalActivity = false
        messageHiddenForExternalActivity = false
        completionAction = null
    }

    fun hideForExternalActivity(): Boolean {
        if (!isAttachedToWindow) return false
        messageHiddenForExternalActivity = isMessageAttachedToWindow
        if (isMessageAttachedToWindow) {
            runCatching { windowManager.removeView(messageView) }
            isMessageAttachedToWindow = false
        }
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
            if (messageHiddenForExternalActivity) {
                messageHiddenForExternalActivity = false
                showOrUpdateMessage()
            }
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
        if (isMessageAttachedToWindow) {
            positionMessageWindow()
            runCatching { windowManager.updateViewLayout(messageView, messageParams) }
        }
    }

    private fun showOrUpdateMessage(): Boolean {
        if (!isAttachedToWindow) return false
        positionMessageWindow()
        return runCatching {
            if (isMessageAttachedToWindow) {
                windowManager.updateViewLayout(messageView, messageParams)
            } else {
                windowManager.addView(messageView, messageParams)
                isMessageAttachedToWindow = true
            }
            true
        }.getOrElse {
            OmniLog.e(TAG, "Unable to show pet message", it)
            false
        }
    }

    private fun positionMessageWindow() {
        messageView.measure(
            View.MeasureSpec.makeMeasureSpec(hintMaxWidthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val measuredWidth = messageView.measuredWidth.coerceAtMost(hintMaxWidthPx)
        val measuredHeight = messageView.measuredHeight
        messageParams.width = measuredWidth
        messageParams.height = measuredHeight
        messageParams.x = params.x - measuredWidth - hintGapPx
        messageParams.y = params.y + (petSizePx - measuredHeight) / 2
    }

    private fun hideMessage() {
        messageView.visibility = View.GONE
        if (isMessageAttachedToWindow) {
            runCatching { windowManager.removeView(messageView) }
            isMessageAttachedToWindow = false
        }
        messageHiddenForExternalActivity = false
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
