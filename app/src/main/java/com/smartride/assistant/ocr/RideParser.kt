package com.smartride.assistant.ocr

import com.smartride.assistant.data.OcrLine
import com.smartride.assistant.data.RideOffer
import com.smartride.assistant.data.ScreenFormat
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns OCR lines into structured ride offers. Pure Kotlin (no Android types) so it can be unit tested.
 *
 * Strategy
 *  1. A fare ("Rs434", "Rs.434", "PKR 434") is the anchor of one offer.
 *  2. Lines are split into per-offer regions at the largest vertical whitespace gap between two consecutive fares.
 *  3. LIST cards (observed inDrive layout): the first two "km" mentions are the pickup distance and the trip distance,
 *     each printed beside the FIRST line of its address. Address text that wraps onto following lines belongs to the
 *     same block. The passenger row ("Name * 4.62 (103)" + "1 min.") ends the card and is excluded.
 *  4. DETAIL screens: address lines are read top to bottom (pickup, destination) and numbers are attached to the nearest address row.
 *
 * Nothing is invented: anything not read stays null and the offer is later classified NEEDS REVIEW.
 */
object RideParser {

    private val FARE = Regex("""(?i)(?:\brs|\bpkr|₨)\s*[.:]?\s*(\d[\d,]{1,5})(?!\d)""")
    private val DIST = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(km|kms|m)\b""")
    private val TIME = Regex("""(?i)(\d{1,3})\s*(?:min|mins|minute|minutes)\b""")
    private val RATING_ONLY = Regex("""^[\s★☆⭐*(]*([1-5][.,]\d{1,2})[\s)]*$""")
    private val RATING_PAREN = Regex("""(?<![\d.])([1-5][.,]\d{1,2})\s*\(\s*\d{1,5}\s*\)""")
    private val STAR = Regex("""[★☆⭐]""")
    private val ELLIPSIS_END = Regex("""[\s,.]*(?:\.{2,}|…)[\s,.]*$""")
    private val BUTTON_WORDS = Regex("""(?i)\b(accept|offer|bid|request|decline|send)\b""")
    private val NOISE_EXACT = setOf(
        "ride", "rides", "pickup", "pick up", "destination", "drop off", "dropoff", "away", "min", "km", "m",
        "cash", "online", "offline", "new", "nearby", "filter", "decline", "accept", "offer", "orders", "order", "from", "to",
        "city", "intercity", "freight", "courier", "search", "searching", "rs", "pkr", "jazzcash", "easypaisa",
        "ride requests", "performance", "requests",
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
    private data class Frag(val raw: String, val y: Int, val conf: Float)
    private data class Addr(val text: String, val y: Int, val conf: Float)

    private fun parseRegion(region: List<OcrLine>, anchor: FareAnchor, format: ScreenFormat): RideOffer? {
        val ordered = region.sortedWith(compareBy({ it.top }, { it.left })).filter { it !== anchor.line }
        val heights = ordered.map { it.height }.filter { it > 0 }.sorted()
        val lineH = if (heights.isEmpty()) 20 else heights[heights.size / 2]
        val tol = max(6, (lineH * 0.7).toInt())

        val dists = ArrayList<Mention<Double>>()
        val times = ArrayList<Mention<Int>>()
        val frags = ArrayList<Frag>()
        val passengerYs = ArrayList<Int>()
        var rating: Double? = null

        for (line in ordered) {
            var t = line.text
            FARE.find(t)?.let { t = t.replaceRange(it.range, " ") } // other fare-like text (button copies)

            // passenger row: "Imran * 4.62 (103)" - never an address
            val rp = RATING_PAREN.find(line.text)
            if (rp != null || STAR.containsMatchIn(line.text)) {
                rating = (rp?.groupValues?.get(1) ?: Regex("""[1-5][.,]\d{1,2}""").find(line.text)?.value)
                    ?.replace(',', '.')?.toDoubleOrNull() ?: rating
                passengerYs.add(line.centerY)
                continue
            }

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
            if (t.isNotBlank()) frags.add(Frag(t, line.centerY, line.confidence))
        }

        val parsed = if (format == ScreenFormat.LIST && dists.isNotEmpty()) {
            parseListBlocks(frags, dists, times, passengerYs, lineH, tol)
        } else {
            parseByRows(frags, dists, times)
        }
        val (pickup, dest) = parsed.addresses
        val used = listOfNotNull(pickup?.conf, dest?.conf, anchor.line.confidence)
        var conf = if (used.isEmpty()) 0f else used.average().toFloat()
        if (pickup == null || dest == null) conf *= 0.5f
        if (parsed.ambiguous) conf *= 0.8f

        return RideOffer(
            fare = anchor.value,
            pickup = pickup?.text,
            destination = dest?.text,
            pickupDistanceKm = parsed.pickupKm,
            pickupEtaMin = parsed.pickupMin,
            tripDistanceKm = parsed.tripKm,
            tripDurationMin = parsed.tripMin,
            rating = rating,
            ocrConfidence = conf,
            format = format,
        )
    }

    private class Parsed(
        val addresses: Pair<Addr?, Addr?>,
        val pickupKm: Double?, val tripKm: Double?,
        val pickupMin: Int?, val tripMin: Int?,
        val ambiguous: Boolean = false,
    )

    /** inDrive list card: [distance beside first address line] ... wrapped lines ... [passenger row]. */
    private fun parseListBlocks(
        frags: List<Frag>, dists: List<Mention<Double>>, times: List<Mention<Int>>,
        passengerYs: List<Int>, lineH: Int, tol: Int,
    ): Parsed {
        val ds = dists.sortedBy { it.y }.take(2)
        val rowYs = passengerYs + times.map { it.y }
        // the passenger row ("Name * 4.62 (103)   1 min.") closes the card
        val cut = rowYs.filter { it > ds.first().y }.minOrNull()
        val limit = if (cut != null) cut - tol else Int.MAX_VALUE

        fun block(k: Int): Addr? {
            val start = ds[k].y - tol
            val end = minOf(if (k + 1 < ds.size) ds[k + 1].y - tol else Int.MAX_VALUE, limit)
            val inBlock = frags.filter { it.y in start until end }.sortedBy { it.y }
            val kept = ArrayList<Frag>()
            for (f in inBlock) {
                if (kept.isNotEmpty() && (f.y - kept.last().y > 2 * lineH || kept.size >= 3)) break
                val c = cleanFragment(f.raw) ?: continue
                kept.add(Frag(c, f.y, f.conf))
            }
            if (kept.isEmpty()) return null
            val text = finalizeAddress(kept.joinToString(" ") { it.raw })
            if (text.count { it.isLetter() } < 3) return null
            return Addr(text, kept.first().y, kept.map { it.conf }.average().toFloat())
        }

        val pickup = block(0)
        val dest = if (ds.size > 1) block(1) else null
        return Parsed(
            pickup to dest,
            pickupKm = ds[0].value, tripKm = ds.getOrNull(1)?.value,
            pickupMin = times.firstOrNull()?.value, tripMin = null,
        )
    }

    /** Detail screens / fallback: one address per text line, numbers attach to the nearest address row. */
    private fun parseByRows(frags: List<Frag>, dists: List<Mention<Double>>, times: List<Mention<Int>>): Parsed {
        val addrs = frags.mapNotNull { f -> cleanAddress(f.raw)?.let { Addr(it, f.y, f.conf) } }
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
            // If everything fell on one side but two values exist, fall back to reading order.
            if (dest != null && dists.size >= 2 && tripKm == null) { pickupKm = dists[0].value; tripKm = dists[1].value }
            if (dest != null && times.size >= 2 && tripMin == null) { pickupMin = times[0].value; tripMin = times[1].value }
        }
        return Parsed(pair, pickupKm, tripKm, pickupMin, tripMin, ambiguous)
    }

    private fun attachedCount(a: Addr, d: List<Mention<Double>>, t: List<Mention<Int>>): Int {
        val tol = 60
        return d.count { abs(it.y - a.y) <= tol } + t.count { abs(it.y - a.y) <= tol }
    }

    // ---- text cleaning ------------------------------------------------------------------------

    /** One OCR fragment of a (possibly wrapped) address: keeps commas and parentheses for the later join. */
    private fun cleanFragment(raw: String): String? {
        val s = raw.replace(Regex("""[•·|→>»«•●○]+"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        if (s.count { it.isLetter() } < 2) return null
        val low = s.lowercase().trim(' ', '.', ',', ':')
        if (low in NOISE_EXACT) return null
        if (NOISE_CONTAINS.any { low.contains(it) }) return null
        return s
    }

    /** Joins wrapped fragments into one clean address: drops the "..." truncation and closes a dangling "(". */
    internal fun finalizeAddress(joined: String): String {
        var s = joined.replace(Regex("""\s+"""), " ").trim()
        s = ELLIPSIS_END.replace(s, "").trim(' ', ',', '.', '-', '–', ':', ';')
        val open = s.count { it == '(' }
        val close = s.count { it == ')' }
        if (open > close) s += ")".repeat(open - close)
        if (close > open) s = s.replace(")", "")
        return s.replace("()", "").trim()
    }

    /** Returns a cleaned single-line address candidate or null if the text is UI noise / has too few letters. */
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
        return s.ifEmpty { null }
    }
}
