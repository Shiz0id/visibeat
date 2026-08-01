package com.visibeat.musicui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween

/**
 * "Duration: 180–260ms"
 * "Easing: decelerate on enter, accelerate on exit"
 */
object AgMotion {
    const val DurationShort = 180
    const val DurationMedium = 220
    const val DurationLong = 260

    // Custom "Semantic" Easings
    val EnteringEasing: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f) // Soft decelerate
    val ExitingEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)  // Sharp accelerate

    fun <T> enterTween(duration: Int = DurationMedium) = tween<T>(
        durationMillis = duration,
        easing = EnteringEasing
    )

    fun <T> exitTween(duration: Int = DurationMedium) = tween<T>(
        durationMillis = duration,
        easing = ExitingEasing
    )
}
