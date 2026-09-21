package com.ninepointnine.desktop.boot

import android.content.Context
import android.content.Intent
import com.ninepointnine.desktop.apps.AutostartResolver
import com.ninepointnine.desktop.apps.AutostartStore
import com.ninepointnine.desktop.apps.AutostartTargetResult

class AutostartBootLauncher(private val context: Context) {
    fun launchConfiguredApplications() {
        val resolver = AutostartResolver(context)
        AutostartStore(context).configuredPackages().sorted().forEach { packageName ->
            val target = resolver.resolve(packageName)
            if (target is AutostartTargetResult.Supported) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(target.target.component)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}
