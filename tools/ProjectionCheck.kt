import com.hoons1994.iphoneduoanimation.FoldProjection
import com.hoons1994.iphoneduoanimation.FoldReveal
import kotlin.math.abs

fun main() {
    var checks = 0
    fun verify(condition: Boolean, message: String) { checks++; check(condition) { message } }
    for (axis in listOf(false, true)) for (moving in listOf(false, true)) for (cover in listOf(false, true)) {
        for (angle in 0..180) for (i in 0..20) {
            val x = i * 60f
            val y = i * 40f
            val tilt = FoldProjection.tiltDegrees(angle.toFloat(), cover)
            val p = FoldProjection.project(x, y, 1200f, 800f, tilt, cover, axis, false, moving)
            verify(p.x.isFinite() && p.y.isFinite(), "nonfinite at $angle")
            if ((!cover && angle == 180) || (cover && angle == 0)) {
                verify(abs(p.x - x) < 0.001f && abs(p.y - y) < 0.001f, "endpoint is not identity")
            }
        }
    }
    for (angle in 0..180) {
        val tilt = FoldProjection.tiltDegrees(angle.toFloat(), false)
        val fixed = FoldProjection.project(250f, 150f, 1200f, 800f, tilt)
        verify(fixed.x == 250f && fixed.y == 150f && fixed.depth == 0f, "fixed pane moved")
        val hinge = FoldProjection.project(600f, 150f, 1200f, 800f, tilt)
        verify(hinge.x == 600f && hinge.y == 150f, "hinge moved")
        val right = FoldProjection.project(1020f, 200f, 1200f, 800f, tilt)
        val left = FoldProjection.project(180f, 200f, 1200f, 800f, tilt, movingFromEnd = false)
        verify(abs(right.x + left.x - 1200f) < 0.002f && abs(right.y - left.y) < 0.002f, "mirror failed")
    }
    verify(FoldProjection.tiltDegrees(150f, false) == 30f, "late inner motion disappeared")
    verify(FoldProjection.tiltDegrees(175f, false) == 5f, "late inner motion disappeared")
    val displaced = FoldProjection.project(1050f, 200f, 1200f, 800f, 60f)
    verify(abs(displaced.x - 1050f) > 100f, "120 degree projection too subtle")
    val reveal = FoldReveal()
    verify(reveal.sample(0f, 0, true) == 0f, "cold startup compensation")
    reveal.arm()
    verify(reveal.sample(0f, 1000, false) == 0f, "hidden display animated")
    verify(reveal.sample(0f, 5000, true) == 28f, "timer consumed before first draw")
    verify(reveal.sample(0f, 5240, true) == 0f && !reveal.needsFrame(), "reveal failed to settle")
    reveal.arm(); reveal.reset()
    verify(reveal.sample(0f, 10000, true) == 0f, "stale reveal survived stop")
    println("PASS: $checks deterministic projection/reveal assertions")
    println("Inner 120 degrees, moving point (1050,200) -> (${displaced.x},${displaced.y})")
}
