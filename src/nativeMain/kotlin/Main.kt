import io.ktor.client.*
import io.ktor.client.engine.winhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking

val client = HttpClient(WinHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 100
        requestTimeoutMillis = 500
        socketTimeoutMillis = 1000
    }
}
val maxRetries = 3


fun main(args: Array<String>) {
    runBlocking {
        for (i in 1..maxRetries) {
            run(args, 63342 + i)
        }
    }
}

private suspend fun run(args: Array<String>, port: Int) {
    try {
        val result = client.get("http://127.0.0.1:${port}/api.addDataSource") {
            parameter(
                "params",
                args.joinToString(" ")
            )
        }
    } catch (ignored: Exception) {

    }
}

