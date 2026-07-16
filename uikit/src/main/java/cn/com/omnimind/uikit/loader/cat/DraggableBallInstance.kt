package cn.com.omnimind.uikit.loader.cat

import cn.com.omnimind.uikit.UIKit

object DraggableBallInstance {
    private var overlay: DraggableBallLoader? = null

    fun getInstance(): DraggableBallLoader? {
        val context = UIKit.appContext ?: return null
        return overlay ?: DraggableBallLoader(context).also { overlay = it }
    }

    fun loadBall(): Boolean {
        val instance = getInstance() ?: return false
        instance.loadBall()
        return instance.isAttachedToWindow
    }

    fun isShowing(): Boolean = overlay?.isAttachedToWindow == true

    fun refreshPetAppearance() {
        getInstance()?.refreshPetAppearance()
    }

    fun collapse() {
        overlay?.collapse()
    }

    fun message(message: String) {
        getInstance()?.message(message)
    }

    fun showTaskCompletionHint(message: String, onClick: (() -> Unit)? = null): Boolean {
        val instance = getInstance() ?: return false
        if (!instance.isAttachedToWindow) return false
        return instance.message(message, onClick)
    }

    fun clearTaskCompletionHint() {
        overlay?.collapseNotChangeState()
    }

    fun destroy() {
        overlay?.destroy()
        overlay = null
    }

    fun hideForExternalActivity(): Boolean {
        return overlay?.hideForExternalActivity() ?: false
    }

    fun restoreAfterExternalActivity(): Boolean {
        return overlay?.restoreAfterExternalActivity() ?: false
    }
}
