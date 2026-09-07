package dev.om1.importer

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object GoogleAuthorization {
    val scopes=listOf("openid","https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/photoslibrary.appendonly","https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata")
    fun request(email:String?=null):AuthorizationRequest=AuthorizationRequest.builder().setRequestedScopes(scopes.map(::Scope)).apply {
        if(!email.isNullOrBlank()) setAccount(Account(email,"com.google"))
    }.build()
    suspend fun identity(context:Context,result:AuthorizationResult,cellular:Boolean):Pair<String,String> = withContext(Dispatchers.IO) {
        check(!result.hasResolution() && result.grantedScopes.containsAll(scopes)) { "Google Photos permission was not granted." }
        val token=checkNotNull(result.accessToken) { "Google did not return authorization." }
        val response=SystemHttp(context,cellular).request("GET","https://www.googleapis.com/oauth2/v3/userinfo",mapOf("Authorization" to "Bearer $token"),byteArrayOf())
        check(response.code==200) { "Unable to verify the Google account (HTTP ${response.code})." }
        val user=JSONObject(response.text());check(user.optBoolean("email_verified")) { "Google email is not verified." }
        user.getString("sub") to user.getString("email")
    }
    suspend fun token(context:Context,accountId:String):String {
        val db=QueueStore.get(context);check(db.setting("accountId")==accountId && accountId.isNotBlank()) { "Reconnect the Google account assigned to these photos." }
        val result=Identity.getAuthorizationClient(context).authorize(request(db.setting("accountEmail"))).await()
        check(!result.hasResolution()) { "Open the app and reconnect Google Photos to renew permission." }
        val verified=identity(context,result,db.setting("cellular","true")=="true")
        check(verified.first==accountId) { "Google returned a different account. Upload paused." }
        return checkNotNull(result.accessToken)
    }
    suspend fun invalidate(context:Context,token:String) {
        Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }
}
