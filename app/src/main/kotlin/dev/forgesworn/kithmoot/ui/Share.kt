package dev.forgesworn.kithmoot.ui

import android.content.Context
import android.content.Intent

/** The Android share sheet: `ACTION_SEND` with a chooser, so the person picks where a link
 *  goes and the chooser itself offers Copy. Never held or logged past this call. */
internal fun share(context: Context, text: String, chooserTitle: String = "Send link") {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}
