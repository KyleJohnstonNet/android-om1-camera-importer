package dev.om1.importer.core

/** Require a fresh standby -> powered -> standby cycle after our own successful wake. */
class StandbyCycle(var phase: Int = ARMED) {
    init { require(phase in ARMED..WAIT_POWERED) }
    fun completed() { phase=WAIT_BASELINE }
    fun observe(controllerPowered:Boolean):Boolean {
        when(phase) {
            WAIT_BASELINE -> if(!controllerPowered) phase=WAIT_POWERED
            WAIT_POWERED -> if(controllerPowered) phase=ARMED
        }
        return phase==ARMED && !controllerPowered
    }
    companion object { const val ARMED=0;const val WAIT_BASELINE=1;const val WAIT_POWERED=2 }
}
