package dev.om1.importer.core

/** A phase deadline, not a promise that Android will deliver the next callback then. */
data class ActivityStatus(val title:String,val detail:String,val until:Long=0) {
    fun countdown(now:Long):String? = if(until==0L) null else {
        val seconds=((until-now).coerceAtLeast(0)+999)/1000
        if(seconds==0L) "Waiting for the next update…" else "$seconds s remaining"
    }
}
