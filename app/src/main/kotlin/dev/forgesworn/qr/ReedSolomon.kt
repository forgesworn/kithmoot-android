package dev.forgesworn.qr

// Reed-Solomon error correction for QR codes: GF(256) with 0x11D, generator
// roots a^0 .. a^(ecCount - 1). Polynomials here are lowest power first,
// except `block`, which is a received codeword block, highest power first.
// Codewords are Ints holding unsigned byte values (0..255).

private fun gfDiv(a: Int, b: Int): Int {
    if (b == 0) throw IllegalArgumentException("Division by zero in GF(256)")
    return if (a == 0) 0 else EXP[(LOG[a] + 255 - LOG[b]) % 255]
}

private fun evaluate(poly: List<Int>, x: Int): Int {
    var y = 0
    for (i in poly.size - 1 downTo 0) y = gfMul(y, x) xor poly[i]
    return y
}

/**
 * Corrects [block] (data codewords then [ecCount] check codewords) in place.
 * Returns the number of codewords corrected, or -1 when the block has more
 * errors than the code can correct.
 */
internal fun correct(block: IntArray, ecCount: Int): Int {
    val n = block.size
    val syndromes = ArrayList<Int>()
    var clean = true
    for (j in 0 until ecCount) {
        var s = 0
        val x = EXP[j]
        for (i in 0 until n) s = gfMul(s, x) xor block[i]
        syndromes.add(s)
        if (s != 0) clean = false
    }
    if (clean) return 0

    // Berlekamp-Massey: the error locator.
    var locator: MutableList<Int> = arrayListOf(1)
    var previous: List<Int> = arrayListOf(1)
    var errors = 0
    var shift = 1
    var lastDiscrepancy = 1
    for (k in 0 until ecCount) {
        var d = syndromes[k]
        for (i in 1..errors) d = d xor gfMul(locator.getOrElse(i) { 0 }, syndromes[k - i])
        if (d == 0) {
            shift++
            continue
        }
        val factor = gfDiv(d, lastDiscrepancy)
        val next: MutableList<Int> = ArrayList(locator)
        for (i in previous.indices) {
            val at = i + shift
            while (next.size <= at) next.add(0)
            next[at] = next[at] xor gfMul(factor, previous[i])
        }
        if (2 * errors <= k) {
            previous = locator
            errors = k + 1 - errors
            lastDiscrepancy = d
            shift = 1
        } else {
            shift++
        }
        locator = next
    }
    while (locator.size > 1 && locator[locator.size - 1] == 0) locator.removeAt(locator.size - 1)
    if (errors * 2 > ecCount || locator.size - 1 != errors) return -1

    // Chien search: a root at a^-p marks an error at power p, index n - 1 - p.
    val powers = ArrayList<Int>()
    for (p in 0 until n) {
        if (evaluate(locator, EXP[(255 - p) % 255]) == 0) powers.add(p)
    }
    if (powers.size != errors) return -1

    // Forney: omega = S * locator mod x^ecCount; e = X * omega(X^-1) / locator'(X^-1) for first root a^0.
    val omega = IntArray(ecCount)
    for (i in 0 until ecCount) {
        var j = 0
        while (j < locator.size && i + j < ecCount) {
            omega[i + j] = omega[i + j] xor gfMul(syndromes[i], locator[j])
            j++
        }
    }
    val derivative = ArrayList<Int>()
    for (i in 1 until locator.size) derivative.add(if (i % 2 == 1) locator[i] else 0)
    val omegaList = omega.toList()
    for (p in powers) {
        val x = EXP[p % 255]
        val xInverse = EXP[(255 - p) % 255]
        val denominator = evaluate(derivative, xInverse)
        if (denominator == 0) return -1
        block[n - 1 - p] = block[n - 1 - p] xor gfMul(x, gfDiv(evaluate(omegaList, xInverse), denominator))
    }
    return errors
}
