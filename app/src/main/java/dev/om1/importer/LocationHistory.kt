package dev.om1.importer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.om1.importer.core.GeoFix
import dev.om1.importer.core.GeoMatch

/** App-private, bounded history; never included in diagnostic logs or backups. */
class LocationHistory internal constructor(context:Context,name:String="location-history.db"):SQLiteOpenHelper(context,name,null,1) {
    companion object {
        private var instance:LocationHistory?=null
        @Synchronized fun get(context:Context)=instance ?: LocationHistory(context.applicationContext).also { instance=it }
        const val RETENTION=30L*24*60*60*1000
    }
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE fixes (time INTEGER PRIMARY KEY, latitude REAL NOT NULL, longitude REAL NOT NULL, accuracy REAL NOT NULL)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=error("Unsupported location history upgrade")
    @Synchronized fun add(fix:GeoFix,now:Long=System.currentTimeMillis()):Boolean {
        if(!fix.usable() || fix.time !in (now-60_000)..(now+10_000)) return false
        writableDatabase.insertWithOnConflict("fixes",null,ContentValues().apply {
            put("time",fix.time);put("latitude",fix.latitude);put("longitude",fix.longitude);put("accuracy",fix.accuracy)
        },SQLiteDatabase.CONFLICT_REPLACE)
        prune(now)
        return true
    }
    @Synchronized fun prune(now:Long=System.currentTimeMillis()) { writableDatabase.delete("fixes","time<?",arrayOf((now-RETENTION).toString())) }
    @Synchronized fun clear() { writableDatabase.delete("fixes",null,null) }
    @Synchronized fun nearest(time:Long):GeoFix? {
        prune()
        val fixes=readableDatabase.rawQuery("SELECT time,latitude,longitude,accuracy FROM fixes WHERE time BETWEEN ? AND ?",
            arrayOf((time-GeoMatch.MAX_AGE).toString(),(time+GeoMatch.MAX_AGE).toString())).use { c ->
            buildList { while(c.moveToNext()) add(GeoFix(c.getLong(0),c.getDouble(1),c.getDouble(2),c.getDouble(3))) }
        }
        return GeoMatch.nearest(time,fixes)
    }
}
