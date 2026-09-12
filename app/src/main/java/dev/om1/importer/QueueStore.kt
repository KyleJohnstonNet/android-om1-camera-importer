package dev.om1.importer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.security.MessageDigest
import dev.om1.importer.core.CameraTimestamp
import dev.om1.importer.core.SessionWindow

/** Transactional app-private queue. Account/destination and discovery time never change on retry. */
class QueueStore internal constructor(context: Context, name: String = "import-queue.db"): SQLiteOpenHelper(context,name,null,2) {
    companion object {
        @Volatile private var instance: QueueStore?=null
        fun get(context: Context)=instance ?: synchronized(this) { instance ?: QueueStore(context.applicationContext).also { instance=it } }
        fun digest(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    val changes=MutableStateFlow(0L)
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE photos (id TEXT PRIMARY KEY, camera TEXT NOT NULL, path TEXT NOT NULL,
            size INTEGER NOT NULL, stamp TEXT NOT NULL, discovered INTEGER NOT NULL,
            account TEXT NOT NULL, album TEXT, state TEXT NOT NULL, local TEXT, sha TEXT,
            upload_url TEXT, upload_token TEXT, granularity INTEGER NOT NULL DEFAULT 262144,
            media_id TEXT, error TEXT, attempts INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX photo_state ON photos(state,discovered)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Sessions are settings JSON, so v1 data needs no destructive migration.
        require(oldVersion == 1 && newVersion == 2) { "Unsupported queue upgrade; preserve existing database." }
    }
    @Synchronized fun setting(key: String, fallback: String=""): String = readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else fallback }
    @Synchronized fun set(key: String,value: String) { writableDatabase.insertWithOnConflict("settings",null,ContentValues().apply { put("key",key);put("value",value) },SQLiteDatabase.CONFLICT_REPLACE); changed() }
    private fun changed() { changes.value++ }
    @Synchronized fun savedSession(): SavedSession? {
        val raw=setting("session").takeIf { it.isNotEmpty() } ?: return null
        val session=SavedSession.read(JSONObject(raw))
        // Pin legacy windows once, rather than interpreting them in a new zone on every read.
        if(!JSONObject(raw).has("zone") || !JSONObject(raw).has("id")) set("session",session.json().toString())
        return session
    }
    fun session(): JSONObject?=savedSession()?.json()
    @Synchronized fun startSession(starts: Long, ends: Long, selectedAlbum: String? = null,
        title: String = if(selectedAlbum == null) "General library" else "Selected album",
        zone: String = java.time.ZoneId.systemDefault().id): SavedSession {
        val account=setting("accountId")
        require(selectedAlbum == null || (selectedAlbum.isNotBlank() && account.isNotBlank()))
        val session=SavedSession(java.util.UUID.randomUUID().toString(),starts,ends,zone,account,selectedAlbum,title)
        val db=writableDatabase;db.beginTransaction()
        try {
            set("session",session.json().toString())
            set("pendingImport","");set("pendingImportReady","false");set("cameraPaused","false")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return session
    }
    @Synchronized fun endSession() {
        val db=writableDatabase;db.beginTransaction()
        try {
            set("session","");set("pendingImport","");set("pendingImportReady","false");set("cameraPaused","true")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun discover(camera: String,files: List<SourcePhoto>, expectedSession: String = savedSession()?.id.orEmpty()): DiscoveryResult {
        val session=savedSession() ?: error("Start a session first.")
        check(session.id==expectedSession) { "Session changed. Retry using the selected session." }
        check(session.camera==null || session.camera==camera) { "This session belongs to a different camera." }
        val window=SessionWindow(session.starts,session.ends)
        val dated=files.map { it to CameraTimestamp.parse(it.stamp,java.time.ZoneId.of(session.zone)) }
        val matched=dated.filter { (_,time) -> time?.let(window::contains) == true }.map { it.first }
        val now=System.currentTimeMillis(); val db=writableDatabase; db.beginTransaction()
        try {
            if(session.camera==null) set("session",session.copy(camera=camera).json().toString())
            matched.forEach {
                val account=session.account
                val album=session.album
                insert(camera,it,now,account,album,"DISCOVERED")
                // A legacy baseline means skipped, not imported. Backfill may claim it,
                // but must never reroute an actual queued or uploaded photo.
                db.update("photos",ContentValues().apply {
                    put("state","DISCOVERED");put("discovered",now);put("account",account);put("album",album)
                },"id=? AND state='BASELINE'",arrayOf(digest("$camera\u0000${it.path}\u0000${it.size}\u0000${it.stamp}")))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed(); return DiscoveryResult(files.size,matched.size,dated.count { it.second==null },
            matched.map { digest("$camera\u0000${it.path}\u0000${it.size}\u0000${it.stamp}") }.toSet())
    }
    private fun insert(camera: String,p: SourcePhoto,time: Long,account: String,album: String?,state: String) {
        writableDatabase.insertWithOnConflict("photos",null,ContentValues().apply {
            put("id",digest("$camera\u0000${p.path}\u0000${p.size}\u0000${p.stamp}"));put("camera",camera);put("path",p.path)
            put("size",p.size);put("stamp",p.stamp);put("discovered",time);put("account",account);put("album",album);put("state",state)
        },SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun rows(where: String="state != 'BASELINE'",args: Array<String> = emptyArray()): List<PhotoRow> = readableDatabase.rawQuery("SELECT * FROM photos WHERE $where ORDER BY discovered,id",args).use { c ->
        buildList { while(c.moveToNext()) {
            fun s(name:String): String?=c.getColumnIndexOrThrow(name).let { if(c.isNull(it)) null else c.getString(it) }
            add(PhotoRow(s("id")!!,s("camera")!!,s("path")!!,s("size")!!.toLong(),s("stamp")!!,s("account")!!,s("album"),s("state")!!,
                s("local"),s("sha"),s("upload_url"),s("upload_token"),s("granularity")!!.toInt(),s("media_id"),s("error"),s("attempts")!!.toInt(),s("retry_at")!!.toLong()))
        } }
    }
    @Synchronized fun update(id:String,vararg values: Pair<String,Any?>) {
        val allowed=setOf("state","local","sha","upload_url","upload_token","granularity","media_id","error","attempts","retry_at")
        writableDatabase.update("photos",ContentValues().apply { values.forEach { (k,v) -> require(k in allowed); when(v) { null->putNull(k);is Long->put(k,v);is Int->put(k,v);else->put(k,v.toString()) } } },"id=?",arrayOf(id));changed()
    }
    @Synchronized fun recover() {
        // A process death during batchCreate has an unknown server outcome: do not blindly create again.
        writableDatabase.execSQL("UPDATE photos SET state='UNCERTAIN', error='Upload creation was interrupted. Reconcile before retrying.' WHERE state='CREATING'")
        changed()
    }
    @Synchronized fun assignUnassigned(account:String) {
        check(account.isNotBlank()); val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("UPDATE photos SET account=? WHERE account='' AND state IN ('DISCOVERED','READY')",arrayOf(account))
            session()?.takeIf { it.getString("account").isEmpty() }?.let { set("session",it.put("account",account).toString()) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() };changed()
    }
}
data class DiscoveryResult(val total:Int,val matched:Int,val invalidTimestamps:Int,val ids:Set<String>)
data class SourcePhoto(val path:String,val size:Long,val stamp:String)
data class PhotoRow(val id:String,val camera:String,val path:String,val size:Long,val stamp:String,val account:String,val album:String?,val state:String,
    val local:String?,val sha:String?,val uploadUrl:String?,val uploadToken:String?,val granularity:Int,val mediaId:String?,val error:String?,val attempts:Int,val retryAt:Long)
