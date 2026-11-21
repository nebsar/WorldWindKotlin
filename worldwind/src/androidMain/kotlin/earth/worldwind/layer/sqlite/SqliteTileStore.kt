package earth.worldwind.layer.sqlite

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Two on-disk layouts are understood; the right one is detected from the table's columns:
 *  - RMAPS:      tiles(x, y, z, s, image) -- z is usually stored as (17 - zoom), y counts from the north (XYZ)
 *  - TILE_INDEX: tile_index(zoom, x, y, data) -- the classic WorldWind "SQLiteMap" format: plain zoom,
 *                y is the WorldWind (south-origin) row exactly as stored, so no inversion and no row flip
 */
actual class SqliteTileStore actual constructor(contentPath: String, readOnly: Boolean) {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        contentPath, null, if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE
    )

    private enum class Layout(
        val table: String, val zoomCol: String, val dataCol: String,
        /** true: honours the zoomInverted flag and stores XYZ (north-origin) rows */
        val rmapsConventions: Boolean
    ) {
        RMAPS("tiles", "z", "image", true),
        TILE_INDEX("tile_index", "zoom", "data", false)
    }

    private val layout: Layout? = detectLayout()

    actual val isOpen: Boolean get() = db.isOpen

    actual fun hasTileTable(): Boolean = layout != null

    actual fun infoValue(key: String): String? {
        val l = layout ?: return null
        return try {
            if (l == Layout.TILE_INDEX) {
                // No info table in this format: derive the zoom range from the tiles themselves.
                val agg = when (key) {
                    "minzoom" -> "MIN"
                    "maxzoom" -> "MAX"
                    else -> return null
                }
                db.rawQuery("SELECT $agg(zoom) FROM tile_index", null).use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
                }
            } else {
                db.rawQuery("SELECT \"$key\" FROM info LIMIT 1", null).use { if (it.moveToFirst()) it.getString(0) else null }
            }
        } catch (e: Exception) {
            null // missing table or column
        }
    }

    actual fun readTile(zoom: Int, column: Int, row: Int, zoomInverted: Boolean): ByteArray? {
        val l = layout ?: return null
        return try {
            if (!db.isOpen) null else db.rawQuery(
                "SELECT ${l.dataCol} FROM ${l.table} WHERE ${l.zoomCol}=? AND x=? AND y=? LIMIT 1",
                arrayOf(fileZoom(zoom, zoomInverted, l).toString(), column.toString(), fileRow(zoom, row, l).toString())
            ).use { if (it.moveToFirst()) it.getBlob(0) else null }
        } catch (e: Exception) {
            null // closed/locked DB or bad row: render as a missing tile instead of crashing the tile pipeline
        }
    }

    actual fun writeTile(zoom: Int, column: Int, row: Int, zoomInverted: Boolean, data: ByteArray) {
        val l = layout ?: return
        val z = fileZoom(zoom, zoomInverted, l)
        val y = fileRow(zoom, row, l)
        db.beginTransaction()
        try {
            db.delete(l.table, "${l.zoomCol}=? AND x=? AND y=?", arrayOf(z.toString(), column.toString(), y.toString()))
            db.insertOrThrow(l.table, null, ContentValues().apply {
                put("x", column)
                put("y", y)
                put(l.zoomCol, z)
                if (l == Layout.RMAPS) put("s", 0)
                put(l.dataCol, data)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    actual fun clearTiles() { layout?.let { db.delete(it.table, null, null) } }

    actual fun close() { if (db.isOpen) db.close() }

    private fun detectLayout(): Layout? = Layout.values().firstOrNull { l ->
        columnsOf(l.table).containsAll(listOf("x", "y", l.zoomCol, l.dataCol))
    }

    private fun columnsOf(table: String): Set<String> = try {
        val cols = HashSet<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val i = c.getColumnIndex("name")
            while (c.moveToNext()) cols += c.getString(i).lowercase()
        }
        cols
    } catch (e: Exception) {
        emptySet()
    }

    private fun fileZoom(zoom: Int, inverted: Boolean, l: Layout) =
        if (l.rmapsConventions && inverted) 17 - zoom else zoom

    // WorldWind rows count from the south (TMS); RMaps/OsmAnd rows count from the north (XYZ).
    // tile_index files store the WorldWind row unchanged.
    private fun fileRow(zoom: Int, row: Int, l: Layout) =
        if (l.rmapsConventions) (1 shl zoom) - 1 - row else row
}