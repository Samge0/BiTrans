package com.samge.bitrans.data

/**
 * One caption entry shown in the transcript list: source text + translation.
 */
data class Caption(
    val id: Long = newId(),
    val source: String,
    val langTag: String,       // detected by SenseVoice: zh/en/ja/ko/yue
    val target: String = "",   // translation (empty while pending)
    val pending: Boolean = true,
    val ts: Long = System.currentTimeMillis(),
) {
    companion object {
        /** reserved id for the in-flight streaming (partial) caption */
        const val PROVISIONAL_ID = -1L

        // monotonic ids: never collide even when two finals land in the same
        // millisecond (force-commit + VAD close) — duplicate LazyColumn keys crash
        private val counter = java.util.concurrent.atomic.AtomicLong(
            System.currentTimeMillis() * 1000,
        )

        fun newId(): Long = counter.incrementAndGet()
    }
}
