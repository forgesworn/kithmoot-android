package dev.forgesworn.qr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A position in picture coordinates: x across, y down. */
data class Point(val x: Double, val y: Double)

internal class Finder(var x: Double, var y: Double, var moduleSize: Double, var count: Int) {
    fun point(): Point = Point(x, y)
}

/** Where a symbol sits in the picture, and how to sample it. */
internal class Placement(
    val topLeft: Point,
    val topRight: Point,
    val bottomLeft: Point,
    val alignment: Point?,
    val size: Int,
    /** Picture coordinates of the centre of module (x, y). */
    val project: (Int, Int) -> Point,
)

/** Mirrors the sign of a JavaScript comparator result: NaN and zero both mean "equal". */
private fun sign(d: Double): Int = if (d < 0) -1 else if (d > 0) 1 else 0

private fun dark(image: BitImage, x: Int, y: Int): Boolean {
    val i = y * image.width + x
    return i >= 0 && i < image.data.size && image.data[i].toInt() == 1
}

private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

private fun distance(a: Finder, b: Finder): Double = hypot(a.x - b.x, a.y - b.y)

/** Runs in 1:1:3:1:1 proportion, each within half a module (the centre within 1.5). */
private fun finderRatio(counts: IntArray, tolerance: Double = 0.5): Boolean {
    var total = 0
    for (c in counts) {
        if (c == 0) return false
        total += c
    }
    if (total < 7) return false
    val module = total / 7.0
    val slack = module * tolerance
    return abs(module - counts[0]) < slack &&
        abs(module - counts[1]) < slack &&
        abs(3 * module - counts[2]) < 3 * slack &&
        abs(module - counts[3]) < slack &&
        abs(module - counts[4]) < slack
}

private fun centreFromEnd(counts: IntArray, end: Double): Double =
    end - counts[4] - counts[3] - counts[2] / 2.0

/**
 * Counts the five runs through (x, y) along direction (dx, dy), starting in
 * the dark centre, and returns the centre along that line, or NaN.
 */
private fun crossCheck(
    image: BitImage,
    x: Int,
    y: Int,
    dx: Int,
    dy: Int,
    maxCount: Int,
    originalTotal: Int,
    tolerance: Double = 0.5,
): Double {
    val width = image.width
    val height = image.height
    fun inside(px: Int, py: Int) = px >= 0 && py >= 0 && px < width && py < height
    val counts = intArrayOf(0, 0, 0, 0, 0)
    var px = x
    var py = y
    while (inside(px, py) && dark(image, px, py)) {
        counts[2]++
        px -= dx
        py -= dy
    }
    while (inside(px, py) && !dark(image, px, py) && counts[1] <= maxCount) {
        counts[1]++
        px -= dx
        py -= dy
    }
    if (!inside(px, py) || counts[1] > maxCount) return Double.NaN
    while (inside(px, py) && dark(image, px, py) && counts[0] <= maxCount) {
        counts[0]++
        px -= dx
        py -= dy
    }
    if (counts[0] > maxCount) return Double.NaN
    px = x + dx
    py = y + dy
    while (inside(px, py) && dark(image, px, py)) {
        counts[2]++
        px += dx
        py += dy
    }
    while (inside(px, py) && !dark(image, px, py) && counts[3] < maxCount) {
        counts[3]++
        px += dx
        py += dy
    }
    if (!inside(px, py) || counts[3] >= maxCount) return Double.NaN
    while (inside(px, py) && dark(image, px, py) && counts[4] < maxCount) {
        counts[4]++
        px += dx
        py += dy
    }
    if (counts[4] >= maxCount) return Double.NaN
    val total = counts.sum()
    // A diagonal crosses the pattern at a longer stretch, so only straight lines compare totals.
    if (!(dx != 0 && dy != 0) && 5 * abs(total - originalTotal) >= 2 * originalTotal) return Double.NaN
    if (!finderRatio(counts, tolerance)) return Double.NaN
    // Position along the line, measured in steps from the start point.
    val end = if (dx != 0) px - x else py - y
    return centreFromEnd(counts, (end * (if (dx + dy > 0) 1 else -1)).toDouble())
}

/**
 * Finds finder pattern candidates, most often confirmed first. [tolerance]
 * is how far a run may stray from its share, in modules; a blurred light
 * ring needs more than the default half module.
 */
internal fun findFinders(image: BitImage, tolerance: Double = 0.5): List<Finder> {
    val width = image.width
    val height = image.height
    val found = ArrayList<Finder>()
    fun consider(counts: IntArray, row: Int, end: Int): Boolean {
        val total = counts.sum()
        var cx = centreFromEnd(counts, end.toDouble())
        val maxCount = counts[2]
        val dy = crossCheck(image, floor(cx).toInt(), row, 0, 1, maxCount, total, tolerance)
        if (dy.isNaN()) return false
        val cy = row + dy
        val dx = crossCheck(image, floor(cx).toInt(), floor(cy).toInt(), 1, 0, maxCount, total, tolerance)
        if (dx.isNaN()) return false
        cx = floor(cx) + dx
        val diagonal = crossCheck(image, floor(cx).toInt(), floor(cy).toInt(), 1, 1, maxCount * 2, total, tolerance + 0.25)
        if (diagonal.isNaN()) return false
        val moduleSize = total / 7.0
        for (f in found) {
            if (abs(cy - f.y) <= moduleSize &&
                abs(cx - f.x) <= moduleSize &&
                (abs(moduleSize - f.moduleSize) <= 1 || abs(moduleSize - f.moduleSize) <= f.moduleSize * 0.25)
            ) {
                val n = f.count + 1
                f.x = (f.x * f.count + cx) / n
                f.y = (f.y * f.count + cy) / n
                f.moduleSize = (f.moduleSize * f.count + moduleSize) / n
                f.count = n
                return true
            }
        }
        found.add(Finder(cx, cy, moduleSize, 1))
        return true
    }

    val skip = max(1, min(3, height / 200))
    var y = skip - 1
    while (y < height) {
        val counts = intArrayOf(0, 0, 0, 0, 0)
        var state = 0
        val row = y * width
        for (x in 0 until width) {
            if (image.data[row + x].toInt() == 1) {
                if (state and 1 != 0) state++
                counts[state]++
            } else if (state and 1 != 0) {
                counts[state]++
            } else if (state == 4) {
                if (finderRatio(counts, tolerance) && consider(counts, y, x)) {
                    counts.fill(0)
                    state = 0
                } else {
                    counts[0] = counts[2]
                    counts[1] = counts[3]
                    counts[2] = counts[4]
                    counts[3] = 1
                    counts[4] = 0
                    state = 3
                }
            } else {
                state++
                counts[state]++
            }
        }
        if (state == 4 && finderRatio(counts, tolerance)) consider(counts, y, width)
        y += skip
    }
    return found.sortedWith { a, b -> b.count - a.count }
}

/**
 * Every plausible top-left, top-right, bottom-left triple, best first: the
 * three should form a right-angled isosceles triangle of like-sized finders.
 */
internal fun finderTriples(finders: List<Finder>): List<Array<Finder>> {
    val pool = finders.take(12)
    class Scored(val score: Double, val triple: Array<Finder>)
    val triples = ArrayList<Scored>()
    for (i in pool.indices) {
        for (j in i + 1 until pool.size) {
            for (k in j + 1 until pool.size) {
                val a = pool[i]
                val b = pool[j]
                val c = pool[k]
                val smallest = min(a.moduleSize, min(b.moduleSize, c.moduleSize))
                val largest = max(a.moduleSize, max(b.moduleSize, c.moduleSize))
                if (largest > 2 * smallest) continue
                val ab = distance(a, b).let { it * it }
                val bc = distance(b, c).let { it * it }
                val ca = distance(c, a).let { it * it }
                // The corner opposite the longest side is the top-left.
                var topLeft = c
                var p = a
                var q = b
                var hyp = ab
                var s1 = bc
                var s2 = ca
                if (bc >= ab && bc >= ca) {
                    topLeft = a; p = b; q = c; hyp = bc; s1 = ab; s2 = ca
                } else if (ca >= ab) {
                    topLeft = b; p = c; q = a; hyp = ca; s1 = ab; s2 = bc
                }
                val side = sqrt((s1 + s2) / 2)
                if (side < 10 * smallest) continue
                val score = (abs(hyp - s1 - s2) + abs(s1 - s2)) / hyp
                if (score > 0.5) continue
                // In picture coordinates (y down) top-right x bottom-left is positive.
                val cross = (p.x - topLeft.x) * (q.y - topLeft.y) - (p.y - topLeft.y) * (q.x - topLeft.x)
                if (cross < 0) {
                    val t = p; p = q; q = t
                }
                triples.add(Scored(score - 0.01 * min(a.count + b.count + c.count, 15), arrayOf(topLeft, p, q)))
            }
        }
    }
    return triples.sortedWith { x, y -> sign(x.score - y.score) }.map { it.triple }
}

/** Module size along a line from one finder centre to another, by its black-white-black runs. */
private fun runModuleSize(image: BitImage, from: Point, to: Point): Double {
    val one = blackWhiteBlack(image, from, to)
    val other = blackWhiteBlack(image, to, from)
    if (one.isNaN()) return other / 7
    if (other.isNaN()) return one / 7
    return (one + other) / 14
}

/** Length of the dark-light-dark run from a finder centre outwards, through and beyond the finder edge. */
private fun blackWhiteBlack(image: BitImage, from: Point, to: Point): Double {
    fun run(startX: Int, startY: Int, targetX: Int, targetY: Int): Double {
        var sx = startX
        var sy = startY
        var tx = targetX
        var ty = targetY
        val steep = abs(ty - sy) > abs(tx - sx)
        if (steep) {
            var t = sx; sx = sy; sy = t
            t = tx; tx = ty; ty = t
        }
        val dx = abs(tx - sx)
        val dy = abs(ty - sy)
        var error = -dx / 2.0
        val xstep = if (sx < tx) 1 else -1
        val ystep = if (sy < ty) 1 else -1
        var state = 0
        var y = sy
        var x = sx
        while (x != tx + xstep) {
            val rx = if (steep) y else x
            val ry = if (steep) x else y
            if (rx < 0 || ry < 0 || rx >= image.width || ry >= image.height) break
            if ((state == 1) == dark(image, rx, ry)) {
                if (state == 2) return hypot((x - sx).toDouble(), (y - sy).toDouble())
                state++
            }
            error += dy
            if (error > 0) {
                if (y == ty) break
                y += ystep
                error -= dx
            }
            x += xstep
        }
        return if (state == 2) hypot((tx + xstep - sx).toDouble(), (ty - sy).toDouble()) else Double.NaN
    }

    val sx = floor(from.x).toInt()
    val sy = floor(from.y).toInt()
    val forward = run(sx, sy, floor(to.x).toInt(), floor(to.y).toInt())
    // The same distance the other way, clipped to the picture.
    var ox = sx - (floor(to.x).toInt() - sx)
    var oy = sy - (floor(to.y).toInt() - sy)
    var scale = 1.0
    if (ox < 0) scale = min(scale, sx.toDouble() / (sx - ox))
    else if (ox >= image.width) scale = min(scale, (image.width - 1 - sx).toDouble() / (ox - sx))
    if (oy < 0) scale = min(scale, sy.toDouble() / (sy - oy))
    else if (oy >= image.height) scale = min(scale, (image.height - 1 - sy).toDouble() / (oy - sy))
    ox = floor(sx + (ox - sx) * scale).toInt()
    oy = floor(sy + (oy - sy) * scale).toInt()
    val backward = run(sx, sy, ox, oy)
    if (forward.isNaN() || backward.isNaN()) return Double.NaN
    return forward + backward - 1
}

private class Candidate(var x: Double, var y: Double, var count: Int)

/**
 * Searches near [estimate] for alignment pattern centres. Candidates seen on
 * two or more rows come first, then nearest the estimate.
 */
private fun findAlignments(image: BitImage, estimate: Point, moduleSize: Double, reach: Int): List<Point> {
    val radius = ceil(reach * moduleSize).toInt()
    val left = max(0, floor(estimate.x - radius).toInt())
    val right = min(image.width - 1, ceil(estimate.x + radius).toInt())
    val top = max(0, floor(estimate.y - radius).toInt())
    val bottom = min(image.height - 1, ceil(estimate.y + radius).toInt())
    if (right - left < moduleSize * 3 || bottom - top < moduleSize * 3) return emptyList()
    fun fits(n: Double) = abs(n - moduleSize) < moduleSize * 0.6
    fun vertical(x: Int, y: Int): Double {
        // Light, dark, light runs through (x, y) going up and down from the dark centre.
        var up = y
        while (up >= top && dark(image, x, up)) up--
        var down = y
        while (down <= bottom && dark(image, x, down)) down++
        val middle = down - up - 1
        if (!fits(middle.toDouble())) return Double.NaN
        var above = 0
        var yy = up
        while (yy >= top && !dark(image, x, yy)) {
            above++
            yy--
        }
        var below = 0
        yy = down
        while (yy <= bottom && !dark(image, x, yy)) {
            below++
            yy++
        }
        if (!fits(above.toDouble()) || !fits(below.toDouble())) return Double.NaN
        return (up + 1 + down) / 2.0
    }
    val found = ArrayList<Candidate>()
    for (y in top..bottom) {
        val runs = ArrayList<Int>()
        var x = left
        while (x <= right && dark(image, x, y)) x++ // skip a dark run cut by the window
        var start = x
        var current = false
        while (x <= right + 1) {
            val d = x <= right && dark(image, x, y)
            if (x <= right && d == current) {
                x++
                continue
            }
            runs.add(x - start)
            start = x
            current = d
            // runs alternate light, dark, light ... ; check the last three when they end in light
            if (runs.size >= 3 && runs.size % 2 == 1) {
                val l1 = runs[runs.size - 3]
                val d1 = runs[runs.size - 2]
                val l2 = runs[runs.size - 1]
                if (fits(l1.toDouble()) && fits(d1.toDouble()) && fits(l2.toDouble())) {
                    val cx = x - l2 - d1 / 2.0
                    val cy = vertical(floor(cx).toInt(), y)
                    if (!cy.isNaN()) {
                        val same = found.firstOrNull { abs(it.x - cx) <= moduleSize && abs(it.y - cy) <= moduleSize }
                        if (same != null) {
                            same.x = (same.x * same.count + cx) / (same.count + 1)
                            same.y = (same.y * same.count + cy) / (same.count + 1)
                            same.count++
                        } else {
                            found.add(Candidate(cx, cy, 1))
                        }
                    }
                }
            }
            x++
        }
    }
    // A candidate seen on one row only counts as three modules further away.
    fun score(f: Candidate) = distance(Point(f.x, f.y), estimate) / moduleSize + (if (f.count >= 2) 0 else 3)
    return found.sortedWith { p, q -> sign(score(p) - score(q)) }.map { Point(it.x, it.y) }
}

/** A projective map from the unit square's corners to four picture points. */
private fun squareToQuad(p0: Point, p1: Point, p2: Point, p3: Point): DoubleArray {
    val dx3 = p0.x - p1.x + p2.x - p3.x
    val dy3 = p0.y - p1.y + p2.y - p3.y
    if (dx3 == 0.0 && dy3 == 0.0) {
        return doubleArrayOf(p1.x - p0.x, p2.x - p1.x, p0.x, p1.y - p0.y, p2.y - p1.y, p0.y, 0.0, 0.0, 1.0)
    }
    val dx1 = p1.x - p2.x
    val dx2 = p3.x - p2.x
    val dy1 = p1.y - p2.y
    val dy2 = p3.y - p2.y
    val den = dx1 * dy2 - dx2 * dy1
    val a13 = (dx3 * dy2 - dx2 * dy3) / den
    val a23 = (dx1 * dy3 - dx3 * dy1) / den
    return doubleArrayOf(
        p1.x - p0.x + a13 * p1.x, p3.x - p0.x + a23 * p3.x, p0.x,
        p1.y - p0.y + a13 * p1.y, p3.y - p0.y + a23 * p3.y, p0.y,
        a13, a23, 1.0,
    )
}

private fun adjoint(m: DoubleArray): DoubleArray {
    val a = m[0]; val b = m[1]; val c = m[2]
    val d = m[3]; val e = m[4]; val f = m[5]
    val g = m[6]; val h = m[7]; val i = m[8]
    return doubleArrayOf(e * i - f * h, c * h - b * i, b * f - c * e, f * g - d * i, a * i - c * g, c * d - a * f, d * h - e * g, b * g - a * h, a * e - b * d)
}

private fun multiply(m: DoubleArray, n: DoubleArray): DoubleArray {
    val out = DoubleArray(9)
    var k = 0
    for (r in 0 until 3) {
        for (c in 0 until 3) out[k++] = m[r * 3] * n[c] + m[r * 3 + 1] * n[3 + c] + m[r * 3 + 2] * n[6 + c]
    }
    return out
}

/** The map taking four module-space points to four picture points. */
private fun quadToQuad(from: List<Point>, to: List<Point>): (Double, Double) -> Point {
    val toPicture = squareToQuad(to[0], to[1], to[2], to[3])
    val fromModules = adjoint(squareToQuad(from[0], from[1], from[2], from[3]))
    // Column-vector convention: picture = toPicture . fromModules^-1 . module.
    val m = multiply(toPicture, fromModules)
    return { x, y ->
        val w = m[6] * x + m[7] * y + m[8]
        Point((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }
}

/**
 * Out of 25, how many modules around [centre] match an alignment pattern
 * (dark centre, light ring, dark ring), stepping by [u] across and [v] down.
 * Perspective changes the local module size, so three scales are tried.
 */
private fun alignmentScore(image: BitImage, centre: Point, u: Point, v: Point): Int {
    var best = 0
    for (scale in doubleArrayOf(0.85, 1.0, 1.15)) {
        var score = 0
        for (j in -2..2) {
            for (i in -2..2) {
                val x = floor(centre.x + (i * u.x + j * v.x) * scale)
                val y = floor(centre.y + (i * u.y + j * v.y) * scale)
                val want = max(abs(i), abs(j)) != 1
                val got = x >= 0 && y >= 0 && x < image.width && y < image.height && dark(image, x.toInt(), y.toInt())
                if (got == want) score++
            }
        }
        best = max(best, score)
    }
    return best
}

/**
 * Turns three finders into sampling maps: one through each likely alignment
 * pattern (when the version has one), then one through the parallelogram's
 * fourth corner. [sizeHint] overrides the estimated module count.
 */
internal fun locate(image: BitImage, triple: Array<Finder>, sizeHint: Int? = null): List<Placement> {
    val topLeftF = triple[0]
    val topRightF = triple[1]
    val bottomLeftF = triple[2]
    val topLeft = topLeftF.point()
    val topRight = topRightF.point()
    val bottomLeft = bottomLeftF.point()
    val moduleSize = (runModuleSize(image, topLeft, topRight) + runModuleSize(image, topLeft, bottomLeft)) / 2
    if (!(moduleSize >= 1)) return emptyList()
    var size: Int
    if (sizeHint != null) {
        size = sizeHint
    } else {
        size = floor((distance(topLeft, topRight) + distance(topLeft, bottomLeft)) / 2 / moduleSize + 0.5).toInt() + 7
        when (size and 3) {
            0 -> size++
            2 -> size--
            3 -> size -= 2
        }
    }
    if (size < 21 || size > 177) return emptyList()
    val corner = Point(topRight.x - topLeft.x + bottomLeft.x, topRight.y - topLeft.y + bottomLeft.y)
    var alignments: List<Point> = emptyList()
    if (size > 21) {
        // Two guesses at the fourth corner: the parallelogram's, and one that
        // follows perspective, where modules grow towards the nearer edge.
        val tl = topLeftF.moduleSize
        val down = bottomLeftF.moduleSize / tl
        val right = topRightF.moduleSize / tl
        val scaled = Point(
            (bottomLeft.x + (topRight.x - topLeft.x) * down + topRight.x + (bottomLeft.x - topLeft.x) * right) / 2,
            (bottomLeft.y + (topRight.y - topLeft.y) * down + topRight.y + (bottomLeft.y - topLeft.y) * right) / 2,
        )
        val correction = 1 - 3.0 / (size - 7)
        val estimates = listOf(corner, scaled).map { c ->
            Point(topLeft.x + correction * (c.x - topLeft.x), topLeft.y + correction * (c.y - topLeft.y))
        }
        // A row or column crosses a turned module on the slant: at 45 degrees a
        // module's run is the diagonal, root 2 times its side.
        val across = distance(topLeft, topRight)
        val slant = max(abs(topRight.x - topLeft.x), abs(topRight.y - topLeft.y)) / across
        val runSize = moduleSize / slant
        // Rank candidates by how well the 5x5 pattern around them matches, read
        // along the code's own axes, then by nearness to a guess.
        val steps = size - 7
        val u = Point((topRight.x - topLeft.x) / steps, (topRight.y - topLeft.y) / steps)
        val v = Point((bottomLeft.x - topLeft.x) / steps, (bottomLeft.y - topLeft.y) / steps)
        fun near(p: Point): Double {
            var best = Double.POSITIVE_INFINITY
            for (e in estimates) {
                val d = distance(p, e)
                if (d.isNaN()) return Double.NaN
                if (d < best) best = d
            }
            return best
        }
        class Scored(val p: Point, val score: Int, val away: Double)
        val scored = ArrayList<Scored>()
        for (reach in intArrayOf(4, 8, 16)) {
            for (estimate in estimates) {
                for (p in findAlignments(image, estimate, runSize, reach)) {
                    if (scored.any { distance(it.p, p) <= moduleSize / 2 }) continue
                    scored.add(Scored(p, alignmentScore(image, p, u, v), near(p)))
                }
            }
            if (scored.any { it.score >= 23 }) break
        }
        alignments = scored
            .filter { it.score >= 20 }
            .sortedWith { a, b ->
                val byScore = b.score - a.score
                if (byScore != 0) byScore else sign(a.away - b.away)
            }
            .take(4)
            .map { it.p }
    }
    val far = size - 3.5
    val finalSize = size
    fun map(alignment: Point?): Placement {
        val project = quadToQuad(
            listOf(
                Point(3.5, 3.5),
                Point(far, 3.5),
                if (alignment != null) Point(finalSize - 6.5, finalSize - 6.5) else Point(far, far),
                Point(3.5, far),
            ),
            listOf(topLeft, topRight, alignment ?: corner, bottomLeft),
        )
        return Placement(topLeft, topRight, bottomLeft, alignment, finalSize) { x, y -> project(x + 0.5, y + 0.5) }
    }
    return alignments.map { map(it) } + map(null)
}
