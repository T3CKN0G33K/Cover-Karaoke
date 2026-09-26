package com.barton.dualscreenhost

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.TextView

class SafeAppWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView {
        return object : AppWidgetHostView(context) {
            override fun getErrorView(): View {
                return TextView(context).apply {
                    text = "Widget layout incompatible with cover screen"
                    setTextColor(Color.RED)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#220000"))
                }
            }
        }
    }
}
