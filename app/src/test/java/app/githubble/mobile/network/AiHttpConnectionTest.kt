package app.githubble.mobile.network
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Test
import org.junit.Assert.*
class AiHttpConnectionTest {
    @Test fun javaBodyHeadersAndErrorStatusSurviveTransportMigration() {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("denied"))
            val c=AiNetwork.open(server.url("/test").toString())
            c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Authorization","Bearer fixture")
            c.setRequestProperty("Content-Type","application/json");c.outputStream.use { it.write("{}".toByteArray()) }
            assertEquals(401,c.responseCode);assertEquals("denied",c.errorStream.bufferedReader().readText())
            val request=server.takeRequest();assertEquals("Bearer fixture",request.getHeader("Authorization"));assertEquals("{}",request.body.readUtf8());c.disconnect()
        } finally {server.shutdown()}
    }
}
