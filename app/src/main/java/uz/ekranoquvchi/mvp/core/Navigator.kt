package uz.ekranoquvchi.mvp.core

import android.graphics.Rect
import android.os.SystemClock
import android.text.Spanned
import android.text.style.URLSpan
import android.view.accessibility.AccessibilityNodeInfo
import uz.ekranoquvchi.mvp.R
import kotlin.math.abs

private const val MAX_NODES = 1500

/** Bitta surishda keyingi fokus nuqtasini qidirishda ko'riladigan tugunlarning eng ko'p soni. */
private const val MAX_STEPS = 400

/** Nutq tezligi / Tafsilotlar rejimi ishlatilmasa, shuncha vaqtdan keyin elementlar rejimiga qaytadi (ms). */
private const val ADJUSTER_HOLD_MS = 12_000L

/** Foydalanuvchi bosishi, tahrirlashi yoki belgilashi mumkin bo'lgan element. */
fun isActionable(n: AccessibilityNodeInfo): Boolean =
    n.isClickable || n.isLongClickable || n.isCheckable || n.isEditable || (n.isFocusable && n.childCount == 0)

fun hasLink(n: AccessibilityNodeInfo): Boolean {
    val t = n.text
    return t is Spanned && t.getSpans(0, t.length, URLSpan::class.java).isNotEmpty()
}

fun hasOwnLabel(n: AccessibilityNodeInfo): Boolean =
    !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank() || !n.hintText.isNullOrBlank()

/**
 * Yorlig'li fokus nuqtasi: yorlig'i bor bargli tugun yoki ichida faqat bosiladigan elementlar (tugmalar) bo'lgan konteyner.
 * Telegram kanalidagi post shunday: matn postning o'zida, ichida esa faqat "fikrlar" va "ulashish" tugmalari bor. Ilgari
 * bunday post nuqta hisoblanmasdi va matni o'qilmasdan o'tib ketardi, faqat tugmalar o'qilardi.
 * Ichida oddiy matnli bola bo'lsa (ro'yxat qatori), nuqta shu bolalarning o'zi bo'ladi, konteyner emas.
 */
fun isLabeledUnit(n: AccessibilityNodeInfo): Boolean {
    if (!hasOwnLabel(n)) return false
    val count = n.childCount
    if (count == 0) return true
    if (n.isScrollable || n.collectionInfo != null || count > 12) return false
    for (i in 0 until count) {
        val c = n.getChild(i) ?: continue
        if (!c.isVisibleToUser) continue
        if (!isActionable(c) && hasOwnLabel(c)) return false
    }
    return true
}

/** Daraxtdan birinchi mos tugunni topadi (ko'rinadigan tugunlar orasidan). */
fun findFirst(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    var visited = 0
    fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (++visited > MAX_NODES || !n.isVisibleToUser) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val r = walk(c)
            if (r != null) return r
        }
        return null
    }
    return walk(root)
}

/**
 * Fokus nuqtalari va navigatsiya rejimlari (TalkBack'dagi "o'qish boshqaruvlari"). Rejim ishoralar xaritasiga qarab
 * almashtiriladi; TalkBack profilida o'ngga/chapga surish doim elementlar bo'yicha yuradi, yuqoriga/pastga surish esa
 * tanlangan rejim bo'yicha (monografiya 18.3 B).
 */
class Navigator {
    /** granularity != 0: harf, so'z, qator yoki xatboshi bo'yicha o'qish (o'qish boshqaruvlari, FR-01). */
    enum class Mode(val labelRes: Int, val granularity: Int = 0) {
        ELEMENTS(R.string.mode_elements),
        HEADINGS(R.string.mode_headings),
        CONTROLS(R.string.mode_controls),
        FIELDS(R.string.mode_fields),
        LINKS(R.string.mode_links),
        CHARACTERS(R.string.mode_characters, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER),
        WORDS(R.string.mode_words, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD),
        LINES(R.string.mode_lines, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE),
        PARAGRAPHS(R.string.mode_paragraphs, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH),

        /** Nutq tezligi: yuqoriga surish tezlashtiradi, pastga surish sekinlashtiradi (TalkBack'dagi "Nutq tezligi"). */
        RATE(R.string.mode_rate),

        /** Tafsilotlar: pastga surish darajani oshiradi, yuqoriga surish kamaytiradi (TalkBack'dagi "Verbosity"). */
        VERBOSITY(R.string.mode_verbosity),
    }

    /** Aka-uka tartibidagi o'rinni eslab qolish: surishda odatda qo'shni elementga o'tiladi (tizimga so'rovlar kamayadi). */
    private val indexHints = HashMap<Int, Int>()

    /** Xotira kam bo'lganda yoki xizmat to'xtaganda keshni tozalash. */
    fun clearCaches() {
        indexHints.clear()
        reversedCache.clear()
    }

    /** Teskari konteyner: bolalari daraxtda pastdan yuqoriga joylashgan ro'yxat (hisob-kitob eslab qolinadi). */
    private val reversedCache = HashMap<Int, Boolean>()

    /**
     * Konteyner bolalari daraxtda pastdan yuqoriga (teskari) joylashganmi. Shunday ro'yxatda o'qish tartibi ko'rinish
     * bo'yicha (yuqoridan pastga) bo'lishi uchun bolalar teskari tartibda ko'riladi. Aks holda "keyingi" xabar tepaga
     * chiqib ketardi. Birinchi ko'rinadigan bolalarning haqiqiy o'rni solishtiriladi.
     */
    fun isReversedContainer(n: AccessibilityNodeInfo): Boolean {
        if (!n.isScrollable) return false
        val count = n.childCount
        if (count < 3) return false
        val key = n.hashCode()
        val known = reversedCache[key]
        if (known != null) return known
        var up = 0
        var down = 0
        try {
            val ra = Rect()
            val rb = Rect()
            var prev: AccessibilityNodeInfo? = null
            for (i in 0 until minOf(count, 16)) {
                val c = n.getChild(i) ?: continue
                if (!c.isVisibleToUser) continue
                val p = prev
                if (p != null) {
                    p.getBoundsInScreen(ra)
                    c.getBoundsInScreen(rb)
                    val dy = rb.centerY() - ra.centerY()
                    val dx = rb.centerX() - ra.centerX()
                    if (abs(dy) > abs(dx) && dy != 0) {
                        if (dy < 0) up++ else down++
                    }
                }
                prev = c
            }
        } catch (_: Throwable) {
        }
        val reversed = up >= 2 && up > down
        if (up + down >= 3) {
            if (reversedCache.size > 64) reversedCache.clear()
            reversedCache[key] = reversed
        }
        return reversed
    }

    /** Bolalar indekslari ko'rinish tartibida: teskari konteynerda kamayib boradi. */
    private fun childOrder(n: AccessibilityNodeInfo): IntProgression {
        val count = n.childCount
        return if (count > 1 && isReversedContainer(n)) ((count - 1) downTo 0) else (0 until count)
    }

    var mode = Mode.ELEMENTS
        private set

    /** Rejim qachon tanlangan yoki oxirgi marta ishlatilgan (uptime, ms): vaqtinchalik rejimlar uchun. */
    private var modeTouchedAt = 0L

    fun cycle(step: Int): Mode {
        val all = Mode.values()
        mode = all[(mode.ordinal + step + all.size) % all.size]
        modeTouchedAt = SystemClock.uptimeMillis()
        return mode
    }

    /** Rejimni to'g'ridan-to'g'ri tanlaydi (o'qish boshqaruvlari menyusi): ko'p marta aylantirish shart emas. */
    fun select(m: Mode): Mode {
        mode = m
        modeTouchedAt = SystemClock.uptimeMillis()
        return m
    }

    /** Nutq tezligi yoki Tafsilotlar kabi "sozlash" rejimida har bir surish rejimni "tirik" saqlaydi. */
    fun keepMode() {
        modeTouchedAt = SystemClock.uptimeMillis()
    }

    /**
     * Nutq tezligi va Tafsilotlar rejimlarida yuqoriga/pastga surish harakatlantirmaydi, qiymatni o'zgartiradi. Foydalanuvchi
     * rejim qolganini unutsa, "yuqoriga-pastga yurib bo'lmayapti" degan holat chiqardi: shuning uchun ma'lum vaqt
     * ishlatilmasa, elementlar rejimiga qaytariladi.
     */
    fun adjusterExpired(now: Long = SystemClock.uptimeMillis()): Boolean =
        (mode == Mode.RATE || mode == Mode.VERBOSITY) && now - modeTouchedAt > ADJUSTER_HOLD_MS

    // ------------------------------------------------------------------ tez navigatsiya
    // Ilgari har bir surishda butun daraxt (1500 tugungacha) tizim orqali bitta-bitta so'ralardi: murakkab ekranlarda
    // bir surish soniyalab davom etardi. Endi faqat keyingi nuqtaga yetguncha bo'lgan tugunlar ko'riladi.

    /** collect(root).firstOrNull() bilan bir xil natija, lekin birinchi nuqta topilishi bilan to'xtaydi. */
    fun firstUnit(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var visited = 0
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (++visited > MAX_NODES || !n.isVisibleToUser) return null
            if (isActionable(n) || n.isScreenReaderFocusable || isLabeledUnit(n)) return n
            for (i in childOrder(n)) {
                val c = n.getChild(i) ?: continue
                val r = walk(c)
                if (r != null) return r
            }
            return null
        }
        return walk(root)
    }

    /**
     * Barmoq tekkan tugunga mos fokus nuqtasi (teginib o'rganish): eng yaqin bosiladigan ota-ona yoki tugunning o'zi;
     * yorlig'i yo'q bo'lsa, yorlig'i bor eng yaqin ota-ona. Qoida collect() bilan bir xil: bosiladigan konteyner
     * ichidagi oddiy matnni o'zi o'qiydi.
     */
    fun unitFor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = start
        var guard = 0
        while (n != null && guard++ < 64) {
            if (isActionable(n)) return n
            n = n.parent
        }
        if (start.isScreenReaderFocusable || hasOwnLabel(start)) return start
        var p: AccessibilityNodeInfo? = start.parent
        guard = 0
        while (p != null && guard++ < 8) {
            if (p.isScreenReaderFocusable || hasOwnLabel(p)) return p
            p = p.parent
        }
        return null
    }

    /**
     * cur'dan keyingi (forward) yoki oldingi fokus nuqtasi, joriy rejimga mos. Daraxt tartibi collect() bilan bir xil.
     * Topilmasa (ro'yxat tugadi yoki qadam chegarasi oshdi) null.
     */
    fun nextUnit(cur: AccessibilityNodeInfo, forward: Boolean, m: Mode = mode): AccessibilityNodeInfo? {
        var n = cur
        var steps = 0
        // Bir qidiruv ichida ota-onalarning "bosiladigan" holati takror so'ralmasligi uchun (har so'rov tizimga murojaat)
        val ancestors = HashMap<Int, Boolean>()
        while (steps++ < MAX_STEPS) {
            val next = (if (forward) stepForward(n) else stepBackward(n)) ?: return null
            if (isUnit(next, ancestors) && matches(next, m)) return next
            n = next
        }
        return null
    }

    /** collect() qoidasi: bosiladigan tugun doim nuqta; oddiy matn esa bosiladigan ota-onasi bo'lmasagina. */
    private fun isUnit(n: AccessibilityNodeInfo, cache: HashMap<Int, Boolean>): Boolean {
        if (isActionable(n)) return true
        if (!(n.isScreenReaderFocusable || isLabeledUnit(n))) return false
        return !hasActionableAncestor(n, cache)
    }

    /**
     * Tugunning ota-onalaridan biri bosiladigan elementmi. cache: ota-ona kaliti -> "o'zi yoki undan yuqorisi bosiladi".
     * Tekshirilgan zanjirdagi hamma ota-onaga bir xil natija yoziladi, shuning uchun ro'yxat qatorlarida
     * har bir tugun uchun butun zanjirni qayta o'tish kerak bo'lmaydi.
     */
    private fun hasActionableAncestor(n: AccessibilityNodeInfo, cache: HashMap<Int, Boolean>): Boolean {
        var p = n.parent
        var guard = 0
        var result = false
        val walked = ArrayList<Int>(8)
        while (p != null && guard++ < 64) {
            val key = p.hashCode()
            val known = cache[key]
            if (known != null) {
                result = known
                break
            }
            walked.add(key)
            if (isActionable(p)) {
                result = true
                break
            }
            p = p.parent
        }
        for (k in walked) cache[k] = result
        return result
    }

    /**
     * bolaning ota-onasidagi tartib raqami. Avval oxirgi marta topilgan o'rin va uning qo'shnilari tekshiriladi
     * (har tekshiruv bitta tizim chaqiruvi); topilmasa hammasi tekshiriladi. Tekshiruv tenglikka asoslangan,
     * shuning uchun eskirgan eslatma xato natija bermaydi.
     */
    private fun indexIn(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        val count = parent.childCount
        val key = parent.hashCode()
        val hint = indexHints[key]
        if (hint != null) {
            val candidates = intArrayOf(hint, hint + 1, hint - 1)
            for (cand in candidates) {
                if (cand in 0 until count && parent.getChild(cand) == child) {
                    indexHints[key] = cand
                    return cand
                }
            }
        }
        for (i in 0 until count) {
            if (parent.getChild(i) == child) {
                if (indexHints.size > 64) indexHints.clear()
                indexHints[key] = i
                return i
            }
        }
        return -1
    }

    /** Daraxt tartibida keyingi ko'rinadigan tugun: avval bolalar, keyin aka-uka, keyin ota-onaning aka-ukasi. */
    private fun stepForward(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in childOrder(n)) {
            val c = n.getChild(i) ?: continue
            if (c.isVisibleToUser) return c
        }
        var x = n
        var guard = 0
        while (guard++ < 64) {
            val p = x.parent ?: return null
            val idx = indexIn(p, x)
            if (idx >= 0) {
                val after: IntProgression =
                    if (isReversedContainer(p)) (idx - 1 downTo 0) else (idx + 1 until p.childCount)
                for (j in after) {
                    val c = p.getChild(j) ?: continue
                    if (c.isVisibleToUser) return c
                }
            }
            x = p
        }
        return null
    }

    /** Daraxt tartibida oldingi tugun: oldingi aka-ukaning eng oxirgi avlodi, aka-uka bo'lmasa ota-onaning o'zi. */
    private fun stepBackward(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val p = n.parent ?: return null
        val idx = indexIn(p, n)
        if (idx >= 0) {
            val before: IntProgression =
                if (isReversedContainer(p)) (idx + 1 until p.childCount) else (idx - 1 downTo 0)
            for (j in before) {
                val s = p.getChild(j) ?: continue
                if (s.isVisibleToUser) return lastDescendant(s)
            }
        }
        return p
    }

    private fun lastDescendant(start: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var x = start
        var guard = 0
        while (guard++ < 64) {
            var next: AccessibilityNodeInfo? = null
            for (i in childOrder(x).reversed()) {
                val c = x.getChild(i) ?: continue
                if (c.isVisibleToUser) {
                    next = c
                    break
                }
            }
            if (next == null) return x
            x = next
        }
        return x
    }

    fun matches(n: AccessibilityNodeInfo, m: Mode = mode): Boolean = when (m) {
        Mode.ELEMENTS -> true
        Mode.HEADINGS -> n.isHeading
        Mode.CONTROLS -> n.isClickable || n.isCheckable || n.isEditable
        Mode.FIELDS -> n.isEditable
        Mode.LINKS -> hasLink(n)
        Mode.CHARACTERS, Mode.WORDS, Mode.LINES, Mode.PARAGRAPHS, Mode.RATE, Mode.VERBOSITY -> true
    }

    /**
     * Fokus nuqtalari daraxt tartibida. Bosiladigan konteyner o'z ichidagi oddiy matnni o'zi o'qiydi
     * (matn alohida nuqta bo'lmaydi), ichidagi bosiladigan elementlar esa alohida nuqta bo'ladi.
     */
    fun collect(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(64)
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, absorbed: Boolean) {
            if (++visited > MAX_NODES || !n.isVisibleToUser) return
            val actionable = isActionable(n)
            var childAbsorbed = absorbed
            if (!absorbed) {
                if (actionable || n.isScreenReaderFocusable || isLabeledUnit(n)) {
                    out.add(n)
                    if (actionable) childAbsorbed = true
                }
            } else if (actionable) {
                out.add(n)
            }
            for (i in childOrder(n)) {
                val c = n.getChild(i) ?: continue
                walk(c, childAbsorbed)
            }
        }
        walk(root, false)
        return out
    }
}
