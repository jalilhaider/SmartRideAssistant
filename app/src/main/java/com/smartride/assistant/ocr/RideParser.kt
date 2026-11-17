package com.smartride.assistant.ocr

import com.smartride.assistant.data.OcrLine
import com.smartride.assistant.data.RideOffer
import com.smartride.assistant.data.ScreenFormat
import kotlin.math.abs

/**
 * Turns OCR lines into structured ride offers. Pure Kotlin (no Android types) so it can be unit tested.
 *
 * Strategy
 *  1. A fare ("Rs.434", "Rs 434", "PKR 434") is the anchor of one offer.
 *  2. Lines are split into per-offer regions at the largest vertical whitespace gap between two
 *     consecutive fare anchors. This works whether the fare sits at the top or the middle of a card.
 *  3. Inside a region, distances/times/rating are pulled out with regexes; the remaining text lines are
 *     address candidates. First candidate = pickup, second = destination (top-to-bottom reading order).
 *  4. Each distance/time is attached to the nearest address row, so "1.4 km" beside the pickup stays with the pickup.
 *
 * Nothing is invented: anything not read stays null and the offer is later classified NEEDS REVIEW.
 */
object RideParser {

    private val FARE = Regex("""(?i)(?:\brs|\bpkr|₨)\s*[.:]?\s*(\d[\d,]{1,5})(?!\d)""")
    private val DIST = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(km|kms|m)\b""")
    private val TIME = Regex("""(?i)(\d{1,3})\s*(?:min|mins|minute|minutes)\b""")
    private val RATING_ONLY = Regex("""^[\s★⭐*(]*([1-5][.,]\d{1,2})[\s)]*$""")
    private val BUTTON_WORDS = Regex("""(?i)\b(accept|offer|bid|request|decline|send)\b""")
    private val NOISE_EXACT = setOf(
        "ride", "rides", "pickup", "pick up", "destination", "drop off", "dropoff", "away", "min", "km", "m",
        "cash", "online", "new", "nearby", "filter", "decline", "accept", "offer", "orders", "order", "from", "to",
        "city", "intercity", "freight", "courier", "search", "searching", "rs", "pkr",
    )
    private val NOISE_CONTAINS = listOf(
        "offer your fare", "your fare", "accept", "decline", "searching for", "no orders", "go online",
        "you are online", "you're online", "tap to", "swipe", "recommended fare", "per km",
    )
    private const val MIN_FARE = 20
    private const val MAX_FARE = 50_000

    /** True when the frame looks like an inDrive-style ride offer screen (fare + a distance on screen). */
    fun detectFormat(lines: List<OcrLine>): ScreenFormat {
        val fares = fareAnchors(lines)
        if (fares.isEmpty()) return ScreenFormat.UNKNOWN
        val hasDistance = lines.any { DIST.containsMatchIn(it.text) }
        if (!hasDistance) return ScreenFormat.UNKNOWN
        return if (fares.size >= 2) ScreenFormat.LIST else ScreenFormat.DETAIL
    }

    fun parse(lines: List<OcrLine>): List<RideOffer> {
        val format = detectFormat(lines)
        if (format == ScreenFormat.UNKNOWN) return emptyList()
        val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val anchors = fareAnchors(sorted)
        val regions = splitRegions(sorted, anchors)
        return anchors.indices.mapNotNull { i -> parseRegion(regions[i], anchors[i], format) }
    }

    // ---- fare anchors -------------------------------------------------------------------------

    private data class FareAnchor(val line: OcrLine, val value: Int)

    private fun fareAnchors(lines: List<OcrLine>): List<FareAnchor> =
        lines.sortedBy { it.top }.mapNotNull { l ->
            val m = FARE.find(l.text) ?: return@mapNotNull null
            val value = m.groupValues[1].replace(",", "").toIntOrNull() ?: return@mapNotNull null
            if (value !in MIN_FARE..MAX_FARE) return@mapNotNull null
            // "Accept Rs.243" / "Offer Rs 250" buttons repeat the fare of the offer; they are not separate offers.
            val rest = l.text.replaceRange(m.range, " ")
            if (BUTTON_WORDS.containsMatchIn(rest)) return@mapNotNull null
            FareAnchor(l, value)
        }

    // ---- region split -------------------------------------------------------------------------

    private fun splitRegions(sorted: List<OcrLine>, anchors: List<FareAnchor>): List<List<OcrLine>> {
        if (anchors.size == 1) return listOf(sorted)
        val cuts = ArrayList<Int>() // y coordinate separating region i and i+1
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i].line
            val b = anchors[i + 1].line
            val between = sorted.filter { it.centerY in a.centerY..b.centerY }.sortedBy { it.top }
            var bestGap = -1
            var cut = (a.centerY + b.centerY) / 2
            for (j in 0 until between.size - 1) {
                val gap = between[j + 1].top - between[j].bottom
                if (gap > bestGap) {
                    bestGap = gap
                    cut = (between[j].bottom + between[j + 1].top) / 2
                }
            }
            cuts.add(cut)
        }
        return anchors.indices.map { i ->
            val lo = if (i == 0) Int.MIN_VALUE else cuts[i - 1]
            val hi = if (i == anchors.size - 1) Int.MAX_VALUE else cuts[i]
            sorted.filter { it.centerY >= lo && it.centerY < hi }
        }
    }

    // ---- one offer ----------------------------------------------------------------------------

    private data class Mention<T>(val value: T, val y: Int)
    private data class Addr(val text: String, val y: Int, val conf: Float)

    private fun parseRegion(region: List<OcrLine>, anchor: FareAnchor, format: ScreenFormat): RideOffer? {
        val dists = ArrayList<Mention<Double>>()
        val times = ArrayList<Mention<Int>>()
        val addrs = ArrayList<Addr>()
        var rating: Double? = null

        for (line in region.sortedWith(compareBy({ it.top }, { it.left }))) {
            if (line === anchor.line) continue
            var t = line.text
            FARE.find(t)?.let { t = t.replaceRange(it.range, " ") } // other fare-like text (button copies)
            for (m in DIST.findAll(line.text)) {
                val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
                val km = if (m.groupValues[2].lowercase().startsWith("k")) n else n / 1000.0
                dists.add(Mention(km, line.centerY))
            }
            t = DIST.replace(t, " ")
            for (m in TIME.findAll(line.text)) {
                m.groupValues[1].toIntOrNull()?.let { times.add(Mention(it, line.centerY)) }
            }
            t = TIME.replace(t, " ")
            val ratingMatch = RATING_ONLY.matchEntire(t)
            if (ratingMatch != null) {
                rating = ratingMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
                continue
            }
            val cleaned = cleanAddress(t)
            if (cleaned != null) addrs.add(Addr(cleaned, line.centerY, line.confidence))
        }

        // choose pickup/destination among address candidates
        var ambiguous = false
        val pair: Pair<Addr?, Addr?> = when {
            addrs.isEmpty() -> null to null
            addrs.size == 1 -> addrs[0] to null
            addrs.size == 2 -> addrs[0] to addrs[1]
            else -> {
                ambiguous = true // extra text such as a passenger name: pick the adjacent pair with most numbers attached
                var best = addrs.size - 2
                var bestScore = -1
                for (i in 0 until addrs.size - 1) {
                    val score = attachedCount(addrs[i], dists, times) + attachedCount(addrs[i + 1], dists, times)
                    if (score >= bestScore) { bestScore = score; best = i }
                }
                addrs[best] to addrs[best + 1]
            }
        }
        val pickup = pair.first
        val dest = pair.second

        var pickupKm: Double? = null; var tripKm: Double? = null
        var pickupMin: Int? = null; var tripMin: Int? = null
        if (pickup != null) {
            val anchorsY = listOfNotNull(pickup.y, dest?.y)
            fun nearest(y: Int): Int = anchorsY.indices.minByOrNull { abs(anchorsY[it] - y) } ?: 0
            for (d in dists) {
                if (nearest(d.y) == 0) { if (pickupKm == null) pickupKm = d.value }
                else if (tripKm == null) tripKm = d.value
            }
            for (t in times) {
                if (nearest(t.y) == 0) { if (pickupMin == null) pickupMin = t.value }
                else if (tripMin == null) tripMin = t.value
            }
            // Detail screens often print "1.4 km" and "2.8 km" on separate rows from the addresses.
            // If everything fell on one side but two values exist, fall back to reading order.
            if (dest != null && dists.size >= 2 && tripKm == null) { pickupKm = dists[0].value; tripKm = dists[1].value }
            if (dest != null && times.size >= 2 && tripMin == null) { pickupMin = times[0].value; tripMin = times[1].value }
        }

        val used = listOfNotNull(pickup?.conf, dest?.conf, anchor.line.confidence)
        var conf = if (used.isEmpty()) 0f else used.average().toFloat()
        if (pickup == null || dest == null) conf *= 0.5f
        if (ambiguous) conf *= 0.8f

        return RideOffer(
            fare = anchor.value,
            pickup = pickup?.text,
            destination = dest?.text,
            pickupDistanceKm = pickupKm,
            pickupEtaMin = pickupMin,
            tripDistanceKm = tripKm,
            tripDurationMin = tripMin,
            rating = rating,
            ocrConfidence = conf,
            format = format,
        )
    }

    private fun attachedCount(a: Addr, d: List<Mention<Double>>, t: List<Mention<Int>>): Int {
        val tol = 60
        return d.count { abs(it.y - a.y) <= tol } + t.count { abs(it.y - a.y) <= tol }
    }

    /** Returns a cleaned address candidate or null if the text is UI noise / has too few letters. */
    internal fun cleanAddress(raw: String): String? {
        var s = raw.replace(Regex("""[•·|→>»«•●○]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', ',', '.', ':', ';')
        // drop only unbalanced parentheses (e.g. a dangling "(" left after removing "(1.4 km)")
        if (s.count { it == '(' } > s.count { it == ')' }) s = s.replace("(", "")
        if (s.count { it == ')' } > s.count { it == '(' }) s = s.replace(")", "")
        s = s.trim()
        if (s.count { it.isLetter() } < 3) return null
        val low = s.lowercase()
        if (low in NOISE_EXACT) return null
        if (NOISE_CONTAINS.any { low.contains(it) }) return null
        s = s.trim()
        return s.ifEmpty { null }
    }
}
