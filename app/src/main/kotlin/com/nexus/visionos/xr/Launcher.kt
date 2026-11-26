package com.nexus.visionos.xr

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.visionos.R

/**
 * Opens the phone's other apps. Android does not let a normal app show them inside its own
 * windows, so they take the whole screen, and the user is told before leaving.
 */
internal fun appLauncher(context: Context, shell: Shell): SpatialApp {
    val manager = context.packageManager
    val own = shell.activity.packageName
    val installed = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.packageName != own }
        .map { it to it.loadLabel(manager).toString() }
        .sortedBy { it.second.lowercase() }
    val root = FrameLayout(context)
    lateinit var show: () -> Unit
    show = {
        root.removeAllViews()
        root.addView(context.scrolling {
            text(context.getString(if (installed.isEmpty()) R.string.apps_none else R.string.apps_note), 12f)
            installed.chunked(5).forEach { chunk ->
                row(height = context.px(94)) {
                    chunk.forEach { (info, label) ->
                        val item = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            background = glassStates(body = 0x14, radius = context.px(18).toFloat())
                            isClickable = true
                            setOnClickListener {
                                root.removeAllViews()
                                root.addView(context.vertical {
                                    gravity = Gravity.CENTER
                                    text(context.getString(R.string.apps_confirm, label), 17f).gravity = Gravity.CENTER
                                    row(height = context.px(64)) {
                                        key(context.getString(R.string.open_app), tint = 0x5AB0FF) {
                                            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                                .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            runCatching { shell.activity.startActivity(launch) }
                                            show()
                                        }
                                        key(context.getString(R.string.dismiss)) { show() }
                                    }
                                })
                            }
                        }
                        val image = ImageView(context)
                        runCatching { image.setImageDrawable(info.loadIcon(manager)) }
                        item.addView(image, LinearLayout.LayoutParams(context.px(46), context.px(46)))
                        item.addView(TextView(context).apply {
                            text = label
                            textSize = 11f
                            setTextColor(-1)
                            gravity = Gravity.CENTER
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(-1, -2))
                        addView(item, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(context.px(5), context.px(5), context.px(5), context.px(5)) })
                    }
                    repeat(5 - chunk.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
                }
            }
        })
    }
    show()
    return SpatialApp(root)
}
