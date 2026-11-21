package earth.worldwind.layer.sqlite

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

object SQLHelper {
    fun establishConnection(file: File): Connection? {
        var c: Connection? = null
        try {
            Class.forName("org.sqlite.JDBC")
            c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath())
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return c
    }

    fun executeQuery(connection: Connection, sql: String?): ResultSet? {
        val stmt = createStatement(connection)
        try {
            return stmt!!.executeQuery(sql)
        } catch (e: SQLException) {
        }
        return null
    }

    private fun createStatement(connection: Connection): Statement? {
        try {
            return connection.createStatement()
        } catch (e: SQLException) {
        }
        return null
    }

    fun execute(connection: Connection, sql: String?) {
        val stmt = createStatement(connection)
        try {
            stmt!!.execute(sql)
            stmt.close()
        } catch (e: SQLException) {
            e.printStackTrace()
        }
    }
}