package by.bebuliby.chrona.tracking

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

object TrackingPermission {
    fun granted(context: Context): Boolean = context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.let { s ->
            s.packageName == context.packageName && s.name == ShortsAccessibilityService::class.java.name
        } }
}
