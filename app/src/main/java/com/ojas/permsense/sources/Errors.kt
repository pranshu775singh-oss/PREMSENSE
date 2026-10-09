package com.ojas.permsense.sources

/**
 * One-line description of an exception and its causes, for text a person reads. The bare message
 * ("T1 transaction failed.") hides the one fact needed to fix it, such as
 * TransactionTooLargeException or DeadObjectException.
 */
internal fun Throwable.brief(): String {
    val parts = ArrayList<String>(4)
    val seen = HashSet<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.add(current) && parts.size < 4) {
        val msg = current.message?.trim().orEmpty()
        parts += if (msg.isEmpty()) current.javaClass.simpleName else "${current.javaClass.simpleName}: $msg"
        current = current.cause
    }
    return parts.joinToString(" <- ")
}
