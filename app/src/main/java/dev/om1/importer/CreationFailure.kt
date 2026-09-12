package dev.om1.importer

/** A per-item Google RPC status is not an HTTP status. */
class MediaCreationRejected(val code:Int):IllegalStateException("Google could not create this photo (code $code). Original retained.")

enum class CreationRecovery { RETRY_CREATE, UPLOAD_AGAIN, RECONCILE }

fun creationRecovery(error: Exception):CreationRecovery = when {
    error is MediaCreationRejected && error.code==3 -> CreationRecovery.UPLOAD_AGAIN
    error is MediaCreationRejected -> CreationRecovery.RETRY_CREATE
    error is CloudFailure && error.status==400 -> CreationRecovery.UPLOAD_AGAIN
    error is CloudFailure && error.status in setOf(401,403,404,429) -> CreationRecovery.RETRY_CREATE
    else -> CreationRecovery.RECONCILE
}
