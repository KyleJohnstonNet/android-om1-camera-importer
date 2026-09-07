package dev.om1.importer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.security.MessageDigest

/** Transactional app-private queue. Account/destination and discovery time never change on retry. */
class QueueStore private constructor(context: Context): SQLiteOpenHelper(context,"import-queue.db",null,1) {
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
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported queue upgrade; preserve existing database.") }
    @Synchronized fun setting(key: String, fallback: String=""): String = readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else fallback }
    @Synchronized fun set(key: String,value: String) { writableDatabase.insertWithOnConflict("settings",null,ContentValues().apply { put("key",key);put("value",value) },SQLiteDatabase.CONFLICT_REPLACE); changed() }
    private fun changed() { changes.value++ }
    fun session(): JSONObject?=setting("session").takeIf { it.isNotEmpty() }?.let(::JSONObject)
    @Synchronized fun begin(camera: String, files: List<SourcePhoto>, includeExisting: Boolean) {
        val now=System.currentTimeMillis()
        val account=setting("accountId")
        val session=JSONObject().put("camera",camera).put("starts",now).put("ends",now+8*60*60*1000L).put("account",account)
        val db=writableDatabase; db.beginTransaction()
        try {
            files.forEach {
                if(includeExisting) db.delete("photos","id=? AND state='BASELINE'",arrayOf(digest("$camera\u0000${it.path}\u0000${it.size}\u0000${it.stamp}")))
                insert(camera,it,now,account,if(includeExisting) albumAt(now,account) else null,if(includeExisting) "DISCOVERED" else "BASELINE")
            }
            set("session",session.toString());db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }
    fun albumAt(now: Long,account: String): String? = setting("albumId").takeIf { it.isNotBlank() && setting("albumAccount")==account && now<setting("albumUntil","0").toLong() }
    @Synchronized fun discover(camera: String,files: List<SourcePhoto>) {
        val session=session() ?: error("Start a new-photo session first.")
        check(session.getString("camera")==camera) { "This session belongs to a different camera." }
        check(System.currentTimeMillis()<session.getLong("ends")) { "Session ended. Start a new session to discover more photos." }
        val now=System.currentTimeMillis(); val db=writableDatabase; db.beginTransaction()
        try {
            files.forEach { insert(camera,it,now,session.getString("account"),albumAt(now,session.getString("account")),"DISCOVERED") }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
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
data class SourcePhoto(val path:String,val size:Long,val stamp:String)
data class PhotoRow(val id:String,val camera:String,val path:String,val size:Long,val stamp:String,val account:String,val album:String?,val state:String,
    val local:String?,val sha:String?,val uploadUrl:String?,val uploadToken:String?,val granularity:Int,val mediaId:String?,val error:String?,val attempts:Int,val retryAt:Long)
