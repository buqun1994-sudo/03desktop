package com.ninepointnine.desktop.debug

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.ninepointnine.desktop.system.GlobalBackActionGateway

// Keep the legacy component name so existing accessibility grants survive upgrades.
class NavigationDemoAccessibilityService : AccessibilityService() {
    private val globalBackExecutor = GlobalBackActionGateway.Executor {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        GlobalBackActionGateway.attach(globalBackExecutor)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        GlobalBackActionGateway.detach(globalBackExecutor)
        super.onDestroy()
    }
}
