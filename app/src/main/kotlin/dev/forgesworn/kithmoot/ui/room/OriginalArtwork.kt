package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.R

fun originalArtworkDrawable(slug: String): Int = when (slug) {
    "laugh" -> R.drawable.km_laugh
    "facepalm" -> R.drawable.km_facepalm
    "mindblown" -> R.drawable.km_mindblown
    "cool" -> R.drawable.km_cool
    "shrug" -> R.drawable.km_shrug
    "celebrate" -> R.drawable.km_celebrate
    "angry" -> R.drawable.km_angry
    "love" -> R.drawable.km_love
    "cry" -> R.drawable.km_cry
    "sideeye" -> R.drawable.km_sideeye
    "popcorn" -> R.drawable.km_popcorn
    "micdrop" -> R.drawable.km_micdrop
    "thumbsup" -> R.drawable.km_thumbsup
    "thumbsdown" -> R.drawable.km_thumbsdown
    "slowclap" -> R.drawable.km_slowclap
    "eyeroll" -> R.drawable.km_eyeroll
    "waiting" -> R.drawable.km_waiting
    "exhausted" -> R.drawable.km_exhausted
    "wtf" -> R.drawable.km_wtf
    "melting" -> R.drawable.km_melting
    "plotting" -> R.drawable.km_plotting
    "moon" -> R.drawable.km_moon
    "coffee" -> R.drawable.km_coffee
    "handshake" -> R.drawable.km_handshake
    else -> error("Unknown original artwork")
}
