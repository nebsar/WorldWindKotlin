package earth.worldwind.layer.sqlite

import java.io.InputStream

class SQLiteMapTile(val zoom: Int, val column: Int, val row: Int, val data: InputStream?)