package com.kazumaproject.custom_keyboard.view

import android.view.View
import com.kazumaproject.custom_keyboard.R

internal fun View.flickInputRotationDegrees(): Float =
    rotation * ((getTag(R.id.flick_rotation_follow_percent) as? Int) ?: 100).coerceIn(0, 100) / 100f

internal fun View.setFlickRotationFollowPercent(percent: Int) {
    setTag(R.id.flick_rotation_follow_percent, percent.coerceIn(0, 100))
}
