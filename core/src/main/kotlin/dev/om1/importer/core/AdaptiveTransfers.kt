package dev.om1.importer.core

/**
 * Measures acknowledged photo bytes, without speed-test traffic. Probes one extra
 * slot at a time and keeps it only when aggregate throughput improves by 10%.
 * All times are monotonic milliseconds; callbacks arrive from parallel uploads.
 */
class AdaptiveTransfers(private val maximum:Int=8,private val initial:Int=3,private val clock:()->Long) {
    init { require(maximum in 1..10 && initial in 1..maximum) }
    companion object {
        const val MAX=8
        private const val WINDOW=10_000L
        private const val COOLDOWN=30_000L
    }
    @Volatile var limit=initial
        private set
    private var network:Long?=null
    private var initialized=false
    private var since=clock()
    private var bytes=0L
    private var samples=0
    private var saturated=true
    private var baseline=0.0
    private var probeFrom:Int?=null
    private var cooldownUntil=0L
    private var lastFailure=Long.MIN_VALUE

    @Synchronized fun networkChanged(id:Long?) {
        if(initialized && network==id) return
        network=id;initialized=true;limit=initial;baseline=0.0;probeFrom=null
        cooldownUntil=0L;lastFailure=Long.MIN_VALUE;resetWindow()
    }

    @Synchronized fun acknowledged(count:Int,networkId:Long?) {
        // A response from the previous route must not train the new route.
        if(networkId!=network || count<=0) return
        bytes+=count;samples++
    }

    @Synchronized fun congested() {
        val now=clock()
        // Several concurrent failures are one congestion event.
        if(lastFailure!=Long.MIN_VALUE && now-lastFailure<WINDOW) return
        lastFailure=now;limit=maxOf(1,limit/2)
        probeFrom=null;baseline=0.0;cooldownUntil=now+COOLDOWN
        resetWindow()
    }

    @Synchronized fun tick(active:Int):Int {
        val now=clock()
        // Don't compare a lower target against the old transfers still draining,
        // or treat the reserved priority slot as capacity gained by a probe.
        saturated=saturated && active==limit
        if(now-since<WINDOW) return limit
        val rate=bytes.toDouble()/(now-since)
        if(saturated && samples==0) {
            congested()
            return limit
        }
        if(!saturated || samples<limit || rate<=0) {
            // An emptying queue or setup work isn't evidence of a bandwidth limit.
            probeFrom?.let { limit=it }
            probeFrom=null;baseline=0.0
        } else if(now>=cooldownUntil) {
            val previous=probeFrom
            if(previous!=null) {
                if(rate<baseline*1.10) {
                    limit=previous;baseline=0.0;cooldownUntil=now+COOLDOWN
                } else {
                    baseline=rate
                }
                probeFrom=null
            } else if(baseline>0 && rate<baseline*0.70) {
                limit=maxOf(1,limit/2);baseline=0.0;cooldownUntil=now+COOLDOWN
            } else {
                baseline=rate
                if(limit<maximum) { probeFrom=limit;limit++ }
            }
        }
        resetWindow()
        return limit
    }

    private fun resetWindow() { since=clock();bytes=0;samples=0;saturated=true }
}
