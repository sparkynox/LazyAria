package aria.sparkynox.lazyaria.utils

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class GestureHandler(
    context: Context,
    target: View,
    private val onGesture: (Gesture) -> Unit,
) {

    enum class Gesture { SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT }

    private val MIN_DIST = 120f
    private val MIN_VEL = 100f

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
            val e1 = e1 ?: return false
            val dX = e2.x - e1.x
            val dY = e2.y - e1.y

            return if (abs(dY) > abs(dX) && abs(dY) > MIN_DIST && abs(vY) > MIN_VEL) {
                onGesture(if (dY > 0) Gesture.SWIPE_DOWN else Gesture.SWIPE_UP)
                true
            } else if (abs(dX) > MIN_DIST && abs(vX) > MIN_VEL) {
                onGesture(if (dX > 0) Gesture.SWIPE_RIGHT else Gesture.SWIPE_LEFT)
                true
            } else false
        }
    })

    init {
        target.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false // don't consume — WebView still scrolls
        }
    }
}
