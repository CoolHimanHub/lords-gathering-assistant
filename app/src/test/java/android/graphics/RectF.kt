package android.graphics

/**
 * Minimal JVM-test implementation of Android's RectF.
 *
 * Android's framework RectF is a stub in local unit-test execution; this keeps
 * geometry behavior deterministic without changing production code.
 */
class RectF {
    var left: Float
    var top: Float
    var right: Float
    var bottom: Float

    constructor() {
        left = 0f; top = 0f; right = 0f; bottom = 0f
    }

    constructor(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }

    constructor(src: RectF) : this(src.left, src.top, src.right, src.bottom)

    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun centerX(): Float = (left + right) / 2f
    fun centerY(): Float = (top + bottom) / 2f

    fun inset(dx: Float, dy: Float) {
        left += dx; top += dy; right -= dx; bottom -= dy
    }

    fun offset(dx: Float, dy: Float) {
        left += dx; right += dx; top += dy; bottom += dy
    }

    fun set(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    fun contains(r: RectF): Boolean =
        left <= r.left && top <= r.top && right >= r.right && bottom >= r.bottom

    override fun equals(other: Any?): Boolean =
        other is RectF &&
            left == other.left && top == other.top &&
            right == other.right && bottom == other.bottom

    override fun hashCode(): Int =
        (((left.toBits() * 31 + top.toBits()) * 31 + right.toBits()) * 31 + bottom.toBits())
}
