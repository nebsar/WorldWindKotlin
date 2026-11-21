package earth.worldwind.layer.sqlite

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.sql.ResultSet
import java.sql.SQLException


/**
 * A streaming iterator to extract tiles
 */
class TileIterator(private val rs: ResultSet) {
    fun hasNext(): Boolean {
        try {
            val hasNext = rs.next()
            if (!hasNext) {
                close()
            }
            return hasNext
        } catch (e: SQLException) {
            return false
        }
    }

    fun next(): SQLiteMapTile? {
        try {
            val zoom = rs.getInt("zoom")
            val column = rs.getInt("x")
            val row = rs.getInt("y")
            val tile_data: InputStream?
            if (rs.getBytes(4) != null) {
                tile_data = ByteArrayInputStream(rs.getBytes(4))
            } else {
                tile_data = ByteArrayInputStream(byteArrayOf())
            }
            return SQLiteMapTile(zoom, column, row, tile_data)
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return null
    }

    fun close() {
        try {
            rs.close()
        } catch (e: SQLException) {
        }
    }
}