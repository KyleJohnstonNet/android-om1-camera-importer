package dev.om1.importer.core

/** Independently implemented from the camera capability report and targetslot XML. */
object CameraSlots {
    fun validate(slot:Int) { require(slot in 1..2) { "Invalid camera card slot." } }
    fun currentUrl()="http://${CameraEndpoints.HOST}/get_playtargetslot.cgi"
    fun selectUrl(slot:Int):String {
        validate(slot)
        return "http://${CameraEndpoints.HOST}/set_playtargetslot.cgi?targetslot=$slot"
    }
    fun parse(body:String):Int {
        require(body.length<=4096) { "Camera slot response too large." }
        val matches=Regex("<targetslot>\\s*([12])\\s*</targetslot>").findAll(body).toList()
        require(matches.size==1) { "Camera did not report a valid card slot." }
        return matches.single().groupValues[1].toInt()
    }
    fun photoIdentity(camera:String,path:String,size:Long,stamp:String,slot:Int):String {
        require(slot in 0..2)
        return "$camera\u0000$path\u0000$size\u0000$stamp"+if(slot==0) "" else "\u0000slot:$slot"
    }
}
