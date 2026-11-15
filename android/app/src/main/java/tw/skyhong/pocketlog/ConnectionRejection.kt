package tw.skyhong.pocketlog

/** Only an explicit authentication rejection establishes this recovery state. */
object ConnectionRejection {
    fun isKnownFailure(failure: Throwable): Boolean = failure is ApiFailure && failure.status == 401
    fun matches(rejectedDestination: String?, credentials: Credentials?): Boolean =
        rejectedDestination != null && credentials != null && rejectedDestination == JournalQueue.destination(credentials)
}
