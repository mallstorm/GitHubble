package app.githubble.mobile.core.download

data class DownloadSpec(val url: String, val suggestedName: String, val expectedSha256: String? = null)

enum class DownloadStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

data class DownloadProgress(val status: DownloadStatus, val bytesRead: Long = 0, val totalBytes: Long = -1, val error: String? = null)
