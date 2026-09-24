package dev.om1.importer

import kotlinx.coroutines.*

/** Fills an adaptive window continuously; failed transfers drain before a serial retry. */
object CameraImportPipeline {
    data class Result(val imported:Int,val failed:Int)
    suspend fun <T> run(items:List<T>,progress:(Int,Int,List<T>,Int)->Unit,
        fallback:()->Unit,controller:AdaptiveUploads=AdaptiveUploads(maximum=10,initial=2) { System.nanoTime()/1_000_000 },
        transfer:suspend (T)->Boolean):Result = coroutineScope {
        var index=0;var imported=0;var failed=0
        val active=linkedMapOf<Int,Deferred<Boolean>>()
        val retry=mutableListOf<T>()
        while(index<items.size || active.isNotEmpty() || retry.isNotEmpty()) {
            ensureActive()
            for(key in active.keys.toList()) {
                val job=active.getValue(key)
                if(!job.isCompleted) continue
                active.remove(key)
                if(job.await()) imported++ else {
                    controller.congested()
                    retry+=items[key]
                }
            }
            if(retry.isNotEmpty() && active.isEmpty()) {
                fallback()
                for(item in retry) {
                    if(failed>=3) break
                    progress(imported,failed,listOf(item),1)
                    if(transfer(item)) imported++ else failed++
                }
                retry.clear()
            }
            if(failed>=3 && active.isEmpty()) break
            if(retry.isEmpty()) while(index<items.size && active.size<controller.limit && failed<3) {
                val key=index++
                active[key]=async { transfer(items[key]) }
            }
            progress(imported,failed,active.keys.map { items[it] },controller.limit)
            controller.tick(active.size)
            if(active.isNotEmpty()) delay(100)
        }
        Result(imported,failed)
    }
}
