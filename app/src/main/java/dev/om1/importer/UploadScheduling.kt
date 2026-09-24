package dev.om1.importer

/** Adaptive normal transfers, with one additional slot for upload-now requests. */
object UploadScheduling {
    fun select(rows:List<PhotoRow>,active:Set<String>,attempted:Map<String,Pair<Long,Long>>,now:Long,limit:Int=3):List<PhotoRow> {
        require(limit in 1..AdaptiveUploads.MAX)
        val available=rows.filter {
            it.id !in active && attempted[it.id]!=(it.priority to it.retryAt) && it.retryAt<=now
        }.sortedWith(compareBy<PhotoRow> { it.state=="UPLOADED" }.thenByDescending { it.priority })
        val selected=mutableListOf<PhotoRow>()
        for(row in available) {
            val count=active.size+selected.size
            if(count<limit || count<limit+1 && row.priority>0) selected+=row
        }
        return selected
    }
}
