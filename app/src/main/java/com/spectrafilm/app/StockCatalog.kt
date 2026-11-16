/*
 * Spektrafilm for Android — film/print stock catalog. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Loads assets/spektra/catalog.json (via the AssetManager) and turns the raw engine
 * profile ids (e.g. "kodak_portra_400") into friendly, grouped picker entries
 * ("Kodak Portra 400" under "Color Negative (Still)"). Stocks carry a `kind`
 * (film / print_film / print_paper) so the Film profile dropdown can show capture
 * stocks while the Print dropdown shows print papers. Anything the engine reports but
 * the catalog doesn't describe still appears, falling back to its raw id.
 */
package com.spectrafilm.app

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONObject

/** One catalog entry, keyed by its engine profile id. */
data class StockEntry(
    val id: String,
    val displayName: String,
    val manufacturer: String,
    val groupId: String,
    val kind: String,         // "film" | "print_film" | "print_paper"
    val iso: Int?,
    val balance: String?,
    val summary: String,
    val order: Int,
)

/** A catalog group (category) with a human title and sort order. */
data class StockGroup(val id: String, val title: String, val order: Int)

/** True when this entry is a colour-reversal (slide) film — the "view as positive" stocks. */
fun StockEntry.isReversal(): Boolean = groupId == StockCatalog.GROUP_COLOR_REVERSAL

/**
 * "ISO 400 · Tungsten" — whichever of the two the catalog actually has, joined only when both
 * are present. Print papers carry neither and get a blank line rather than a stray separator.
 */
fun StockEntry.specLine(balanceLabel: String? = balance): String =
    listOfNotNull(iso?.let { "ISO $it" }, balanceLabel?.takeIf { it.isNotBlank() })
        .joinToString(" · ")

/**
 * An option shown in a profile dropdown.
 *
 * [spec] and [summary] carry the catalog detail that [StockCatalog.optionsFor] used to throw
 * away: `catalog.json` populates iso/balance/summary for all 28 stocks, and the picker collapsed
 * every entry to a bare name. Choosing a film is the single most product-defining act in this
 * app; it should not be a text menu with the descriptions deleted.
 */
data class ProfileOption(
    val id: String,
    val label: String,
    val groupTitle: String,
    /** Short spec line, e.g. "ISO 400 · Tungsten". Blank when the catalog has neither. */
    val spec: String = "",
    /** One-sentence character note from the catalog. Blank for uncatalogued ids. */
    val summary: String = "",
)

object StockCatalog {

    private const val ASSET_PATH = "spektra/catalog.json"

    /** Catalog group id for colour-reversal (slide) films — the natural "view as positive" stocks. */
    const val GROUP_COLOR_REVERSAL = "color_reversal"

    @Volatile private var groupsCache: List<StockGroup>? = null
    @Volatile private var stocksCache: Map<String, StockEntry>? = null

    private fun ensureLoaded(ctx: Context) {
        if (stocksCache != null && groupsCache != null) return
        val (groups, stocks) = runCatching {
            val text = ctx.assets.open(ASSET_PATH).use { it.readBytes().decodeToString() }
            val root = JSONObject(text)

            val groupsArr = root.optJSONArray("groups")
            val groups = buildList {
                if (groupsArr != null) for (i in 0 until groupsArr.length()) {
                    val g = groupsArr.optJSONObject(i) ?: continue
                    add(StockGroup(g.optString("id"), g.optString("title", g.optString("id")), g.optInt("order", i)))
                }
            }

            val stocksObj = root.optJSONObject("stocks") ?: JSONObject()
            val stocks = LinkedHashMap<String, StockEntry>()
            for (id in stocksObj.keys()) {
                val o = stocksObj.optJSONObject(id) ?: continue
                stocks[id] = StockEntry(
                    id = id,
                    displayName = o.optString("name", id),
                    manufacturer = o.optString("manufacturer", ""),
                    groupId = o.optString("group", ""),
                    kind = o.optString("kind", "film"),
                    iso = if (o.has("iso")) o.optInt("iso") else null,
                    balance = if (o.has("balance")) o.optString("balance") else null,
                    summary = o.optString("summary", ""),
                    order = o.optInt("order", 0),
                )
            }
            groups to (stocks as Map<String, StockEntry>)
        }.getOrDefault(emptyList<StockGroup>() to emptyMap())
        groupsCache = groups
        stocksCache = stocks
    }

    fun stocks(ctx: Context): Map<String, StockEntry> {
        ensureLoaded(ctx); return stocksCache ?: emptyMap()
    }

    fun groups(ctx: Context): List<StockGroup> {
        ensureLoaded(ctx); return groupsCache ?: emptyList()
    }

    fun entry(ctx: Context, id: String): StockEntry? = stocks(ctx)[id]

    /** Friendly display name for a profile id, or the raw id if not catalogued. */
    fun displayName(ctx: Context, id: String): String = stocks(ctx)[id]?.displayName ?: id

    private fun groupTitle(ctx: Context, groupId: String): String =
        stockGroupTitleRes(groupId)?.let(ctx::getString)
            ?: groups(ctx).firstOrNull { it.id == groupId }?.title
            ?: groupId

    /** Localised character note for a catalogued stock, or the asset's English note as fallback. */
    private fun summary(ctx: Context, entry: StockEntry): String =
        stockSummaryRes(entry.id)?.let(ctx::getString) ?: entry.summary

    /** Localised colour-balance word, or the raw asset value as fallback. */
    private fun balanceLabel(ctx: Context, entry: StockEntry): String? {
        val raw = entry.balance?.takeIf { it.isNotBlank() } ?: return null
        return stockBalanceRes(raw)?.let(ctx::getString) ?: raw
    }

    /** Group sort order, falling back to a large value so unknown groups sort last. */
    private fun groupOrder(ctx: Context, groupId: String): Int =
        groups(ctx).firstOrNull { it.id == groupId }?.order ?: Int.MAX_VALUE

    /** True if [id] is a colour-reversal (slide) film — best viewed as a positive (Slide mode). */
    fun isReversalFilm(ctx: Context, id: String): Boolean = entry(ctx, id)?.isReversal() ?: false

    /**
     * Build dropdown options for [available] engine profile ids, filtered to a side:
     * "film" keeps capture stocks (kind == "film"), "print" keeps print media
     * (print_paper / print_film). Catalogued options are sorted by group order then
     * stock order; ids not in the catalog are appended (their group title is the raw id).
     *
     * Profiles the catalog doesn't classify are included on the [forFilm] = true side so
     * they remain selectable rather than vanishing.
     */
    fun optionsFor(ctx: Context, available: List<String>, forFilm: Boolean): List<ProfileOption> {
        val stocks = stocks(ctx)
        val matched = available.filter { id ->
            val e = stocks[id]
            if (e == null) forFilm // uncatalogued profiles fall to the Film side
            else if (forFilm) e.kind == "film" else (e.kind == "print_paper" || e.kind == "print_film")
        }
        return matched.sortedWith(
            compareBy({ groupOrder(ctx, stocks[it]?.groupId ?: "") }, { stocks[it]?.order ?: 0 }, { it }),
        ).map { id ->
            val e = stocks[id]
            ProfileOption(
                id = id,
                label = e?.displayName ?: id,
                groupTitle = e?.let { groupTitle(ctx, it.groupId) }
                    ?: ctx.getString(R.string.stock_group_other),
                spec = e?.let { it.specLine(balanceLabel(ctx, it)) }.orEmpty(),
                summary = e?.let { summary(ctx, it) }.orEmpty(),
            )
        }
    }
}

/** Catalog group ids -> localised picker group titles. Unknown ids fall back to the asset title. */
@StringRes
private fun stockGroupTitleRes(groupId: String): Int? = when (groupId) {
    "color_negative" -> R.string.stock_group_color_negative
    "color_reversal" -> R.string.stock_group_color_reversal
    "motion_picture_negative" -> R.string.stock_group_motion_picture_negative
    "print_film" -> R.string.stock_group_print_film
    "print_paper" -> R.string.stock_group_print_paper
    else -> null
}

/** Stock ids -> localised character notes. Official model names themselves stay in the asset. */
@StringRes
private fun stockSummaryRes(id: String): Int? = when (id) {
    "kodak_portra_160" -> R.string.stock_summary_kodak_portra_160
    "kodak_portra_400" -> R.string.stock_summary_kodak_portra_400
    "kodak_portra_800" -> R.string.stock_summary_kodak_portra_800
    "kodak_portra_800_push1" -> R.string.stock_summary_kodak_portra_800_push1
    "kodak_portra_800_push2" -> R.string.stock_summary_kodak_portra_800_push2
    "kodak_ektar_100" -> R.string.stock_summary_kodak_ektar_100
    "kodak_gold_200" -> R.string.stock_summary_kodak_gold_200
    "kodak_ultramax_400" -> R.string.stock_summary_kodak_ultramax_400
    "fujifilm_pro_400h" -> R.string.stock_summary_fujifilm_pro_400h
    "fujifilm_c200" -> R.string.stock_summary_fujifilm_c200
    "fujifilm_xtra_400" -> R.string.stock_summary_fujifilm_xtra_400
    "kodak_ektachrome_100" -> R.string.stock_summary_kodak_ektachrome_100
    "fujifilm_provia_100f" -> R.string.stock_summary_fujifilm_provia_100f
    "fujifilm_velvia_100" -> R.string.stock_summary_fujifilm_velvia_100
    "kodak_kodachrome_64" -> R.string.stock_summary_kodak_kodachrome_64
    "kodak_vision3_50d" -> R.string.stock_summary_kodak_vision3_50d
    "kodak_vision3_250d" -> R.string.stock_summary_kodak_vision3_250d
    "kodak_vision3_200t" -> R.string.stock_summary_kodak_vision3_200t
    "kodak_vision3_500t" -> R.string.stock_summary_kodak_vision3_500t
    "kodak_verita_200d" -> R.string.stock_summary_kodak_verita_200d
    "kodak_2383" -> R.string.stock_summary_kodak_2383
    "kodak_2393" -> R.string.stock_summary_kodak_2393
    "kodak_portra_endura" -> R.string.stock_summary_kodak_portra_endura
    "kodak_supra_endura" -> R.string.stock_summary_kodak_supra_endura
    "kodak_ultra_endura" -> R.string.stock_summary_kodak_ultra_endura
    "kodak_endura_premier" -> R.string.stock_summary_kodak_endura_premier
    "kodak_ektacolor_edge" -> R.string.stock_summary_kodak_ektacolor_edge
    "fujifilm_crystal_archive_typeii" -> R.string.stock_summary_fujifilm_crystal_archive_typeii
    else -> null
}

@StringRes
private fun stockBalanceRes(balance: String): Int? = when (balance) {
    "daylight" -> R.string.stock_balance_daylight
    "tungsten" -> R.string.stock_balance_tungsten
    else -> null
}
