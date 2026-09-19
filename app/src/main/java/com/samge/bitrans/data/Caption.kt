package com.samge.bitrans.data

/**
 * One caption entry shown in the transcript list: source text + translation.
 */
data class Caption(
    val id: Long = System.currentTimeMillis(),
    val source: String,
    val langTag: String,       // detected by SenseVoice: zh/en/ja/ko/yue
    val target: String = "",   // translation (empty while pending)
    val pending: Boolean = true,
    val ts: Long = System.currentTimeMillis(),
)
