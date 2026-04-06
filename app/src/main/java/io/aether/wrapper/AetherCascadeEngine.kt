package io.aether.wrapper

import java.nio.ByteBuffer
import kotlin.math.*

class AetherCascadeEngine {

    companion object {
        const val CASCADE_VERSION = "4"
        const val TRUST_THRESHOLD = 0.65
    }

    // FIX: Delta Convergence braucht den vorherigen normierten Metrik-Vektor.
    // Python speichert diesen in _delta_history[source_label]. Da die mobile App
    // einen einzigen kontinuierlichen Frame-Stream hat, reicht ein einzelner Slot.
    // @Volatile: FramePipelineService ruft cascade() von Dispatchers.Default auf.
    @Volatile private var prevNormMetrics: DoubleArray? = null

    data class Metrics(
        val entropy: Double, val boltzmann: Double, val zipf: Double,
        val benford: Double, val fourier: Double, val katz: Double,
        val permEntropy: Double, val deltaConvergence: Double,
        val noetherConsistency: Double, val trustScore: Double,
        val lossless: Double = 1.0
    )

    fun cascade(data: ByteArray, prevDelta: Double = 0.0): Metrics {
        if (data.isEmpty()) return emptyMetrics()

        // 1. Shannon Entropy (H)
        val h = calculateShannonEntropy(data)

        // 2. Boltzmann
        val boltzmann = calculateBoltzmann(data)

        // 3. Zipf (Alpha via Log-Log Regression)
        val zipf = calculateZipf(data)

        // 4. Benford Conformance
        val benford = calculateBenford(data)

        // 5. Fourier (Lag-Correlation)
        val fourier = calculateFourier(data)

        // 6. Katz Fractal Dimension
        // FIX: toList().chunked() auf großen Arrays erzeugt Millionen von Objekten → OOM.
        // Index-basierte Verarbeitung: kein Kopieren, kein Boxing.
        val blocks = (0 until data.size step 256).map { start ->
            calculateShannonEntropy(data, start, minOf(start + 256, data.size))
        }
        val katz = calculateKatz(blocks)

        // 7. Permutation Entropy (Order 3)
        val perm = calculatePermutationEntropy(data)

        // 8. Delta Convergence — RMSE zwischen aktuellem und vorherigem normierten Metrik-Vektor.
        // FIX: Alte Formel war (h+boltzmann+...)/sqrt(8) → kein Delta, nur Summe der aktuellen Werte.
        // Python-Referenz (_delta_convergence in analysis_capsule.py):
        //   normiert jeden Wert auf [0,1], speichert in _delta_history, berechnet sqrt(Σ(curr-prev)²/N).
        //   Beim ersten Aufruf (genesis): return 0.0.
        // Normierung analog zu Python:
        //   entropy: bereits h/8 → [0,1] ✓
        //   boltzmann: [0,1] ✓
        //   zipf: Python: zipf/3.0; hier: /2.0 (unser Wertebereich ist [0,2])
        //   benford: [0,1] ✓
        //   fourier/periodicity: [0,1] ✓
        //   katz: bereits /2.5 → [0,1] ✓
        //   perm: [0,1] ✓  (nach PermEnt-Fix auch semantisch korrekt)
        val normMetrics = doubleArrayOf(
            h,
            boltzmann,
            (zipf / 2.0).coerceIn(0.0, 1.0),
            benford,
            fourier,
            katz,
            perm
        )
        val delta = calculateDeltaConvergence(normMetrics)

        // 9. Noether Consistency
        val noether = calculateNoetherConsistency(data, h, prevDelta)

        // 10. Trust Score (Boltzmann-Shannon Divergence Pattern)
        // Formula: trust = (entropy + benford + noether + (1 - abs(boltzmann - entropy/8.0))) / 4.0
        // h is already h/8.0 in calculateShannonEntropy
        val trust = (h + benford + noether + (1.0 - abs(boltzmann - h))) / 4.0

        return Metrics(h, boltzmann, zipf, benford, fourier, katz, perm, delta, noether, trust, 1.0)
    }

    private fun calculateShannonEntropy(data: ByteArray): Double =
        calculateShannonEntropy(data, 0, data.size)

    private fun calculateShannonEntropy(data: ByteArray, from: Int, to: Int): Double {
        val freq = IntArray(256)
        for (i in from until to) freq[data[i].toInt() and 0xFF]++
        val total = (to - from).toDouble()
        if (total == 0.0) return 0.0
        var h = 0.0
        for (f in freq) { if (f > 0) { val p = f / total; h -= p * (ln(p) / ln(2.0)) } }
        return h / 8.0
    }

    private fun calculateBoltzmann(data: ByteArray): Double {
        val counts = IntArray(256); data.forEach { counts[it.toInt() and 0xFF]++ }
        val total = data.size.toDouble()
        val s = -counts.filter { it > 0 }.sumOf { c -> val p = c / total; p * ln(p) }
        return (s / ln(256.0)).coerceIn(0.0, 1.0)
    }

    private fun calculateZipf(data: ByteArray): Double {
        val freq = IntArray(256); data.forEach { freq[it.toInt() and 0xFF]++ }
        val sorted = freq.filter { it > 0 }.sortedDescending()
        if (sorted.size < 2) return 1.0
        var sumX = 0.0; var sumY = 0.0; var sumXY = 0.0; var sumXX = 0.0
        for (i in sorted.indices) {
            val x = ln((i + 1).toDouble()); val y = ln(sorted[i].toDouble())
            sumX += x; sumY += y; sumXY += x * y; sumXX += x * x
        }
        val slope = (sorted.size * sumXY - sumX * sumY) / (sorted.size * sumXX - sumX * sumX)
        return abs(slope).coerceIn(0.0, 2.0)
    }

    private fun calculateBenford(data: ByteArray): Double {
        val counts = DoubleArray(10)
        var total = 0.0
        for (i in 0 until data.size - 3 step 4) {
            val v = abs(ByteBuffer.wrap(data, i, 4).int)
            if (v > 0) {
                val firstDigit = v.toString()[0] - '0'
                if (firstDigit in 1..9) { counts[firstDigit]++; total++ }
            }
        }
        if (total == 0.0) return 0.0
        var deviation = 0.0
        for (d in 1..9) {
            val expected = log10(1.0 + 1.0 / d)
            deviation += abs(counts[d] / total - expected)
        }
        return (1.0 - deviation / 1.5).coerceIn(0.0, 1.0)
    }

    private fun calculateFourier(data: ByteArray): Double {
        val n = data.size; if (n < 10) return 0.0
        var maxScore = 0.0
        for (lag in 1..min(64, n / 3)) {
            var matches = 0
            for (i in 0 until n - lag) { if (data[i] == data[i + lag]) matches++ }
            val score = matches.toDouble() / (n - lag)
            if (score > maxScore) maxScore = score
        }
        return maxScore
    }

    private fun calculateKatz(values: List<Double>): Double {
        val n = values.size; if (n < 2) return 1.0
        var curveLength = 0.0
        for (i in 1 until n) curveLength += hypot(1.0, values[i] - values[i - 1])
        var maxDiameter = 0.0
        for (i in 1 until n) {
            val d = hypot(i.toDouble(), values[i] - values[0])
            if (d > maxDiameter) maxDiameter = d
        }
        if (curveLength == 0.0) return 1.0
        val fd = log10(n.toDouble()) / (log10(maxDiameter / curveLength) + log10(n.toDouble()))
        return fd.coerceIn(1.0, 2.5) / 2.5
    }

    private fun calculatePermutationEntropy(data: ByteArray): Double {
        // FIX: Vorige Formel war NICHT invertiert: pe/ln(6) → 0.0=geordnet, 1.0=chaotisch.
        // Python-Referenz (attractor_engine.py): return 1.0 - h/max_h → 1.0=geordnet, 0.0=chaotisch.
        // Konsequenz vorher: hohe PermEnt = chaotisch → zog Trust runter statt rauf.
        // Jetzt: 1.0 - pe/ln(6.0) → konsistent mit Python-Semantik.
        if (data.size < 3) return 0.5  // Python gibt 0.5 zurück wenn zu wenig Daten
        val patterns = mutableMapOf<List<Int>, Int>()
        for (i in 0 until data.size - 2) {
            val triple = listOf(data[i].toInt() and 0xFF, data[i + 1].toInt() and 0xFF, data[i + 2].toInt() and 0xFF)
            val sortedIdx = triple.indices.sortedBy { triple[it] }
            patterns[sortedIdx] = patterns.getOrDefault(sortedIdx, 0) + 1
        }
        var h = 0.0
        val total = (data.size - 2).toDouble()
        for (count in patterns.values) { val p = count / total; h -= p * ln(p) }
        val maxH = ln(6.0)  // ln(3!) = ln(6) für order=3
        return (1.0 - h / maxH).coerceIn(0.0, 1.0)
    }

    private fun calculateNoetherConsistency(data: ByteArray, h: Double, deltaPenalty: Double): Double {
        val sampleSize = min(data.size, 512)
        // FIX: data.sliceArray().distinct() boxed jedes Byte → Set<Byte>. Index-basiert stattdessen.
        val seen = BooleanArray(256)
        var distinct = 0
        for (i in 0 until sampleSize) {
            val b = data[i].toInt() and 0xFF
            if (!seen[b]) { seen[b] = true; distinct++ }
        }
        val periodicityProxy = distinct / 256.0

        // FIX 1: toList().chunked(256) boxed jeden Byte und erzeugt List<List<Byte>> → OOM auf großen Frames.
        //         Index-basierte Berechnung: kein Kopieren, kein Boxing (konsistent mit Katz-Fix oben).
        // FIX 2: calculateShannonEntropy gibt bereits h/8.0 zurück (Bereich [0,1]).
        //         Das alte /8.0 auf entropyDelta war eine Doppel-Normalisierung → entropyDelta lag immer
        //         in [0, 0.125], d.h. (1 - entropyDelta) war immer ≥ 0.875 → Noether fast immer maximal.
        val blockSize = 256
        val hFirst = if (data.size >= blockSize)
            calculateShannonEntropy(data, 0, blockSize)
        else
            calculateShannonEntropy(data, 0, data.size)
        val hLast = if (data.size >= blockSize)
            calculateShannonEntropy(data, data.size - blockSize, data.size)
        else
            hFirst
        val entropyDelta = abs(hLast - hFirst) // beide in [0,1], kein weiteres /8

        val noether = (0.40 * periodicityProxy) + (0.30 * (1.0 - entropyDelta)) + (0.30 * (1.0 - deltaPenalty))
        return noether.coerceIn(0.0, 1.0)
    }

    private fun calculateDeltaConvergence(current: DoubleArray): Double {
        val prev = prevNormMetrics
        prevNormMetrics = current.copyOf()
        if (prev == null) return 0.0  // genesis: kein vorheriger Frame → 0.0 wie Python
        val sumSq = current.indices.sumOf { i -> (current[i] - prev[i]).pow(2) }
        return sqrt(sumSq / current.size).coerceIn(0.0, 1.0)
    }

    private fun emptyMetrics() = Metrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
}

object NT {
    const val CONST = 0; const val X = 1; const val ADD = 7; const val MUL = 9
    const val BRIDGE = 25; const val INTERFERE = 33
}

data class Node(val t: Int, var iv: Int = 0, var v: Double = 0.0, var a: Node? = null, var b: Node? = null)

class EvalContext(
    val x: Double, val y: Double, val z: Double, val t: Double,
    val i: Int, val nVal: Int, val mem: DoubleArray = DoubleArray(256),
    val stack: DoubleArray = DoubleArray(64), var sp: Int = 0
)

fun evalNode(node: Node?, ctx: EvalContext, budget: Int = 64): Double {
    if (node == null || budget <= 0) return 0.0
    fun ev(n: Node?) = evalNode(n, ctx, budget - 1)
    fun clamp(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
    fun clampD(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v

    return when (node.t) {
        NT.CONST -> node.v
        NT.X -> ctx.x
        NT.ADD -> ev(node.a) + ev(node.b)
        NT.MUL -> ev(node.a) * ev(node.b)
        NT.BRIDGE -> {
            val aVal = ev(node.a); val dVal = ev(node.b)
            val ua = clamp(abs(aVal.roundToInt()), 0, 255)
            val ud = clamp(abs(dVal.roundToInt()), 0, 255)
            val sa = (ctx.i + node.iv) and 7
            val sb = (ctx.nVal + node.iv + 3) and 7
            val mix = ((ua shl sa) xor (ud ushr sb) xor (ua ushr ((sb + 1) and 7)) xor (ud shl ((sa + 1) and 7))) and 255
            val bitMix = mix / 255.0
            val syn = abs(aVal - dVal) * 0.06 + abs(bitMix - ctx.y) * 0.32 + abs(ctx.z - ctx.t) * 0.02
            ctx.mem[252] = ctx.mem[252] * 0.78 + syn * 0.22
            ctx.mem[253] = ctx.mem[253] * 0.76 + (ua xor mix) / 255.0 * 0.24
            ctx.mem[254] = ctx.mem[254] * 0.76 + (ud xor mix) / 255.0 * 0.24
            val pos = if (ctx.nVal > 1) clampD(ctx.i.toDouble() / (ctx.nVal - 1), 0.0, 1.0) else clampD(ctx.x, 0.0, 1.0)
            val phA = pos * 2 * PI
            val phB = phA + (node.iv and 7) / 7.0 * PI
            var bridge = (aVal * 0.22 + dVal * 0.18 + bitMix * 0.34 + syn
                    + ctx.mem[node.iv and 255] * 0.08 + ctx.mem[252] * 0.08
                    + ctx.mem[253] * 0.06 + ctx.mem[254] * 0.04 + ctx.z * 0.02)
            bridge += (cos(phA) * aVal + cos(phB) * dVal) * 0.04
            ctx.mem[node.iv and 255] = bridge
            if (ctx.sp < 64) ctx.stack[ctx.sp++] = bridge
            bridge
        }
        NT.INTERFERE -> {
            val sA = ev(node.a); val sB = ev(node.b)
            val pos = if (ctx.nVal > 1) clampD(ctx.i.toDouble() / (ctx.nVal - 1), 0.0, 1.0) else clampD(ctx.x, 0.0, 1.0)
            val ps = (node.iv and 15) / 15.0 * 2 * PI
            val wA = sA * cos(pos * 2 * PI)
            val wB = sB * cos(pos * 2 * PI + ps)
            val ampA = abs(sA); val ampB = abs(sB)
            val result = clampD((wA + wB + ampA + ampB) * 127.5 / (ampA + ampB + 1e-9) + 127.5, 0.0, 255.0)
            ctx.mem[node.iv and 255] = result
            if (ctx.sp < 64) ctx.stack[ctx.sp++] = result
            result
        }
        else -> 0.0
    }
}
