package com.ars.gpslogger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class GpsRow(val id: Long, val lat: Double, val lon: Double,
                  val alt: Double, val acc: Double, val ts: String)

class GpsDatabase(context: Context) :
    SQLiteOpenHelper(context, "gps_buffer.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE locations (
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                lat      REAL, lon REAL, alt REAL, acc REAL,
                ts       TEXT,
                uploaded INTEGER DEFAULT 0
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS locations")
        onCreate(db)
    }

    fun insert(lat: Double, lon: Double, alt: Double, acc: Double, ts: String) {
        val v = ContentValues().apply {
            put("lat", lat); put("lon", lon); put("alt", alt)
            put("acc", acc); put("ts", ts);  put("uploaded", 0)
        }
        writableDatabase.insert("locations", null, v)
    }

    fun getPending(): List<GpsRow> {
        val rows = mutableListOf<GpsRow>()
        val c = readableDatabase.rawQuery(
            "SELECT id,lat,lon,alt,acc,ts FROM locations WHERE uploaded=0 ORDER BY id ASC", null)
        while (c.moveToNext())
            rows.add(GpsRow(c.getLong(0), c.getDouble(1), c.getDouble(2),
                            c.getDouble(3), c.getDouble(4), c.getString(5)))
        c.close()
        return rows
    }

    fun markUploaded(id: Long) {
        val v = ContentValues().apply { put("uploaded", 1) }
        writableDatabase.update("locations", v, "id=?", arrayOf(id.toString()))
    }
}
