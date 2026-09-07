package dev.om1.camerahelper

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.om1.importer.core.CameraWifiCredentials
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One camera profile, encrypted at rest. The encryption key never leaves Android Keystore. */
object CameraProfileStore {
    private const val ALIAS="om1-camera-profile-v1"
    private fun file(context: Context)=AtomicFile(File(context.filesDir,"camera-profile.bin"))
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun save(context: Context, ssid: String, password: String, wpa3: Boolean, bluetoothName: String? = null, bluetoothPassword: String? = null) {
        require(ssid.isNotBlank() && ssid.toByteArray().size<=32 && password.length in 8..63)
        val previous=load(context)?.takeIf { it.ssid==ssid && it.password==password }
        val bleName=bluetoothName ?: previous?.bluetoothName
        val blePassword=bluetoothPassword ?: previous?.bluetoothPassword
        val plaintext=JSONObject().put("version",1).put("ssid",ssid).put("password",password)
            .put("wpa3",wpa3).put("bluetoothName",bleName).put("bluetoothPassword",blePassword).put("lastConnected",java.time.Instant.now().toString()).toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        cipher.updateAAD(byteArrayOf(1))
        val encrypted=try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        check(cipher.iv.size==12)
        val atomic=file(context)
        val stream=atomic.startWrite()
        try { stream.write(byteArrayOf(1)); stream.write(cipher.iv); stream.write(encrypted); atomic.finishWrite(stream) }
        catch(e:Exception) { atomic.failWrite(stream); throw e }
    }
    @Synchronized fun load(context: Context): CameraWifiCredentials? {
        val atomic=file(context)
        if(!atomic.baseFile.exists()) return null
        val encoded=atomic.openRead().use { input ->
            val bytes=ByteArray(16385)
            var size=0
            while(size<bytes.size) { val n=input.read(bytes,size,bytes.size-size); if(n<0) break; size+=n }
            require(size in 30..16384) { "Invalid saved camera profile." }
            bytes.copyOf(size)
        }
        require(encoded[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,encoded.copyOfRange(1,13)))
            updateAAD(byteArrayOf(1))
        }
        val decoded=cipher.doFinal(encoded.copyOfRange(13,encoded.size))
        try {
            val record=JSONObject(decoded.toString(Charsets.UTF_8))
            require(record.getInt("version")==1)
            val ssid=record.getString("ssid"); val password=record.getString("password")
            require(ssid.isNotBlank() && ssid.toByteArray().size<=32 && password.length in 8..63)
            return CameraWifiCredentials(ssid,password,record.getBoolean("wpa3"),
                record.optString("bluetoothName").takeIf { it.isNotBlank() },
                record.optString("bluetoothPassword").takeIf { it.isNotBlank() })
        } finally { decoded.fill(0) }
    }
    @Synchronized fun forget(context: Context) {
        file(context).delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(ALIAS) }
    }
}
