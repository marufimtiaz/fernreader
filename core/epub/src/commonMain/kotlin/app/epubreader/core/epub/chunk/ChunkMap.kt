package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.PositionMap
import app.epubreader.core.epub.TextPos
import app.epubreader.core.epub.cfi.Cfi
import app.epubreader.core.epub.cfi.CfiPath
import app.epubreader.core.epub.cfi.CfiStep
import app.epubreader.core.epub.markup.Paths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Two-way mapping between a book's original documents and the virtual (chunked) ones the reader is given.
 * Stored positions are always ORIGINAL; the reader only ever sees VIRTUAL ones (plan: Global Constraints).
 *
 * Spine coordinates: CFI spine steps count every element child of `<spine>` ([spine], one [Slot] each, in order),
 * the way the reader computes them; text positions and section indices count only the slots that are sections
 * (`path != null`), as `BookPackage.spine` and the reader's sections do. A split document's slot becomes one slot
 * per part, in place.
 *
 * Inside a split document the container is the body or, when [SplitDoc.wrapped], the body's single element child.
 * Part `p` holds the container's element children `[childOffset, childOffset + childCount)` and the text gaps
 * around them, so a container-child step `s` (even = element, odd = text gap) maps by `±2 * childOffset`.
 * Everything else in the CFI (assertions, deeper steps, terminals) is kept verbatim.
 */
@Serializable
data class ChunkMap(
    val policyKey: String,
    val spine: List<Slot>,
    val docs: Map<String, SplitDoc>,
) : PositionMap {
    /** An element child of `<spine>`: the section's zip path (null if not a section, e.g. an unknown idref) and its id. */
    @Serializable data class Slot(val path: String?, val id: String? = null)

    /** [bodyStep]: CFI step of `<body>` under `<html>` (the same in the original and in every part). */
    @Serializable data class SplitDoc(val path: String, val bodyStep: Int, val wrapped: Boolean, val parts: List<Part>, val idToPart: Map<String, Int>)

    /** [textStart]/[textLength]: the part's range in the original document's canonical text (TextExtractor). */
    @Serializable data class Part(val path: String, val childOffset: Int, val childCount: Int, val textStart: Int, val textLength: Int)

    private class VSlot(val orig: Int, val part: Int)
    private class VSection(val orig: Int, val path: String, val part: Int)
    private class Mapped(val steps: List<CfiStep>, val slot: Int, val doc: SplitDoc?, val part: Int)

    val isIdentity: Boolean get() = docs.isEmpty()

    /** Original sections (zip paths), as `BookPackage.spine`. */
    val spinePaths: List<String> by lazy { spine.mapNotNull { it.path } }
    val partPaths: Set<String> by lazy { docs.values.flatMapTo(HashSet()) { d -> d.parts.map { it.path } } }

    private val vSlots: List<VSlot> by lazy {
        spine.flatMapIndexed { j, s ->
            val d = s.path?.let(docs::get)
            if (d == null) listOf(VSlot(j, -1)) else d.parts.indices.map { VSlot(j, it) }
        }
    }
    private val firstVSlot: IntArray by lazy {
        IntArray(spine.size).also { a -> for (i in vSlots.indices.reversed()) a[vSlots[i].orig] = i }
    }
    private val vSections: List<VSection> by lazy {
        spinePaths.flatMapIndexed { i, path ->
            val d = docs[path]
            if (d == null) listOf(VSection(i, path, -1)) else d.parts.mapIndexed { p, part -> VSection(i, part.path, p) }
        }
    }
    private val firstVSection: IntArray by lazy {
        IntArray(spinePaths.size).also { a -> for (i in vSections.indices.reversed()) a[vSections[i].orig] = i }
    }
    private val partOwner: Map<String, SplitDoc> by lazy { docs.values.flatMap { d -> d.parts.map { it.path to d } }.toMap() }

    /** Virtual sections (zip paths of documents or parts), in reading order. */
    val virtualSpinePaths: List<String> by lazy { vSections.map { it.path } }

    // ---- hrefs (zip paths with an optional fragment) ---------------------------------------------------------------

    fun hrefToVirtual(originalHref: String): String {
        val path = originalHref.substringBefore('#')
        val d = docs[path] ?: return originalHref
        val frag = originalHref.substringAfter('#', "")
        val p = if (frag.isEmpty()) 0 else d.idToPart[Paths.percentDecode(frag)] ?: 0
        return d.parts[p].path + if ('#' in originalHref) "#$frag" else ""
    }

    fun hrefToOriginal(virtualHref: String): String {
        val path = virtualHref.substringBefore('#')
        val d = partOwner[path] ?: return virtualHref
        return d.path + virtualHref.substring(path.length)
    }

    // ---- text positions --------------------------------------------------------------------------------------------

    override fun textToVirtual(p: TextPos): TextPos {
        if (isIdentity) return p
        val path = spinePaths.getOrNull(p.spineIndex) ?: return p
        val base = firstVSection[p.spineIndex]
        val d = docs[path] ?: return TextPos(base, p.offset)
        val i = d.parts.indexOfLast { it.textStart <= p.offset }.coerceAtLeast(0)
        return TextPos(base + i, p.offset - d.parts[i].textStart)
    }

    override fun textToOriginal(p: TextPos): TextPos {
        if (isIdentity) return p
        val v = vSections.getOrNull(p.spineIndex) ?: return p
        val start = if (v.part < 0) 0 else docs.getValue(spinePaths[v.orig]).parts[v.part].textStart
        return TextPos(v.orig, start + p.offset)
    }

    // ---- CFIs ------------------------------------------------------------------------------------------------------

    override fun cfiToOriginal(virtualCfi: String): String = mapCfi(virtualCfi, toVirtual = false)
    override fun cfiToVirtual(originalCfi: String): String = mapCfi(originalCfi, toVirtual = true)

    private fun mapCfi(cfi: String, toVirtual: Boolean): String {
        if (isIdentity) return cfi
        val c = Cfi.parse(cfi) ?: return cfi
        val f: (List<CfiStep>) -> Mapped? = if (toVirtual) ::pointToVirtual else ::pointToOriginal
        if (!c.isRange) return f(c.parent.steps)?.let { Cfi(CfiPath(it.steps, c.parent.terminal)).toString() } ?: cfi
        val a = c.startPoint()
        val b = c.endPoint()
        val ma = f(a.steps) ?: return cfi
        var mb = f(b.steps) ?: return cfi
        var endTerminal = b.terminal
        if (ma.slot != mb.slot) {
            // The reader shows one part at a time: a range saved under another policy that now spans parts ends
            // after the start's part ("after" step of its container). Across documents only the start is kept.
            val d = ma.doc
            val k = d?.let(::childStepPos) ?: 0
            if (d == null || ma.steps.size < k) return Cfi(CfiPath(ma.steps, a.terminal)).toString()
            mb = Mapped(ma.steps.take(k) + CfiStep(2 * d.parts[ma.part].childCount + 2), ma.slot, d, ma.part)
            endTerminal = ""
        }
        // Keep the reader's own split into parent/start/end when the mapped points still share that parent.
        val n = c.parent.steps.size
        if (ma.steps.size >= n && mb.steps.size >= n && ma.steps.subList(0, n) == mb.steps.subList(0, n))
            return Cfi(CfiPath(ma.steps.take(n)), CfiPath(ma.steps.drop(n), a.terminal), CfiPath(mb.steps.drop(n), endTerminal)).toString()
        return Cfi.range(CfiPath(ma.steps, a.terminal), CfiPath(mb.steps, endTerminal)).toString()
    }

    /** Position of the container-child step: /spine/itemref!/body/<k> or /spine/itemref!/body/wrapper/<k>. */
    private fun childStepPos(d: SplitDoc) = if (d.wrapped) 4 else 3

    private fun slotOf(step: CfiStep, count: Int): Int? =
        if (step.index % 2 != 0) null else (step.index / 2 - 1).takeIf { it in 0 until count }

    private fun pointToVirtual(steps: List<CfiStep>): Mapped? {
        if (steps.size < 2) return null
        val j = slotOf(steps[1], spine.size) ?: return null
        val base = firstVSlot[j]
        val d = spine[j].path?.let(docs::get)
        val out = steps.toMutableList()
        if (d == null) {
            out[1] = steps[1].copy(index = 2 * (base + 1))
            return Mapped(out, base, null, -1)
        }
        var p = 0
        if (steps.size >= 3 && steps[2].index == d.bodyStep) {
            val k = childStepPos(d)
            val wrapperStep = if (d.wrapped && steps.size > 3) steps[3].index else 2
            when {
                wrapperStep > 2 -> p = d.parts.size - 1     // text after the wrapper lives in the last part
                wrapperStep < 2 -> p = 0
                steps.size > k -> {
                    val s = steps[k].index
                    val elem = if (s % 2 == 0) s / 2 - 1 else (s - 1) / 2
                    p = d.parts.indexOfLast { it.childOffset <= elem }.coerceAtLeast(0)
                    out[k] = steps[k].copy(index = s - 2 * d.parts[p].childOffset)
                }
            }
        }
        out[1] = CfiStep(2 * (base + p + 1), null, steps[1].indirect)
        return Mapped(out, base + p, d, p)
    }

    private fun pointToOriginal(steps: List<CfiStep>): Mapped? {
        if (steps.size < 2) return null
        val v = slotOf(steps[1], vSlots.size) ?: return null
        val vs = vSlots[v]
        val out = steps.toMutableList()
        if (vs.part < 0) {
            out[1] = steps[1].copy(index = 2 * (vs.orig + 1))
            return Mapped(out, vs.orig, null, -1)
        }
        out[1] = CfiStep(2 * (vs.orig + 1), spine[vs.orig].id, steps[1].indirect)
        val d = docs.getValue(spine[vs.orig].path!!)
        val part = d.parts[vs.part]
        val off = part.childOffset
        if (steps.size == 2 && vs.part > 0) {
            // A whole section (the bare itemref step): the part's first element in the original.
            out[1] = out[1].copy(indirect = true)
            out += CfiStep(d.bodyStep)
            if (d.wrapped) out += CfiStep(2)
            out += CfiStep(2 * (off + 1))
        } else if (steps.size >= 3 && steps[2].index == d.bodyStep) {
            val k = childStepPos(d)
            when {
                d.wrapped && steps.size > 3 && steps[3].index != 2 -> {}   // text around the wrapper: same steps
                steps.size > k -> {
                    val s = steps[k].index
                    out[k] = steps[k].copy(index = when {
                        s == 0 && vs.part > 0 -> 2 * off + 1                                       // before the part
                        s >= 2 * part.childCount + 2 && vs.part < d.parts.size - 1 -> 2 * (off + part.childCount) + 1 // after it
                        else -> s + 2 * off
                    })
                }
                vs.part > 0 -> {
                    // The part's container itself: its first element in the original.
                    if (d.wrapped && out.size == 3) out += CfiStep(2)
                    out += CfiStep(2 * (off + 1))
                }
            }
        }
        return Mapped(out, vs.orig, d, vs.part)
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(s: String): ChunkMap = json.decodeFromString(serializer(), s)

        fun identity(spine: List<Slot>, policyKey: String) = ChunkMap(policyKey, spine, emptyMap())
    }
}
