package app.streampocket

object StreamRecovery {
    /** Retry a forbidden stream exactly once, after discarding its extracted URLs. */
    fun <T> run(prepare: (forceRefresh: Boolean) -> Unit, download: () -> T, onRefresh: () -> Unit = {}, cancelled: () -> Boolean = { false }): T {
        prepare(false)
        return try { download() } catch (e: Exception) {
            if (cancelled() || !forbidden(e)) throw e
            onRefresh()
            prepare(true)
            download()
        }
    }
    fun forbidden(e: Exception): Boolean = generateSequence<Throwable>(e) { it.cause }
        .any { Regex("(?i)(HTTP(?: Error)?[ :]+403|403[ :]+Forbidden)").containsMatchIn(it.message.orEmpty()) }
}
