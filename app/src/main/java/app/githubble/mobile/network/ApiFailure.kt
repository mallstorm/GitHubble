package app.githubble.mobile.network
class ApiFailure(message: String, val statusCode: Int = 0) : java.io.IOException(message)
