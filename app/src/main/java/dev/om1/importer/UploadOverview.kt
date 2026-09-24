package dev.om1.importer

object UploadOverview {
    fun describe(pending:List<PhotoRow>,active:Int,limit:Int,saving:Boolean,enabled:Boolean,
        account:Boolean,connected:Boolean,wifi:Boolean,cellular:Boolean,now:Long):String = when {
        saving -> "Paused: battery saver is on."
        !enabled -> "Paused: queued uploads are disabled."
        !account -> "Waiting for Google Photos sign-in."
        active>0 -> "$active photo(s) in progress · adaptive limit $limit + one priority slot."
        pending.isEmpty() -> "Up to date. No photos waiting to upload."
        !connected -> "Waiting for an internet connection. Retries are automatic."
        !cellular && !wifi && pending.none { it.priority>0 } -> "Waiting for Wi-Fi. Cellular uploads are disabled."
        pending.all { it.retryAt>now } -> "Retry in ${((pending.minOf { it.retryAt }-now+999)/1000)} s · ${pending.size} queued. Android may defer the start."
        else -> "${pending.size} queued · waiting for the upload worker. Android may defer background work."
    }
}
