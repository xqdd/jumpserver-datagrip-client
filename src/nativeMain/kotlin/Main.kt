import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

val client = HttpClient(WinHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 100
        requestTimeoutMillis = 500
        socketTimeoutMillis = 500
    }
}
val maxRetries = 5


fun main(args: Array<String>) = runBlocking {
    for (i in 0..maxRetries) {
        launch { run(args, 63342 + i) }
        launch { run(args, 54640 + i) }
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

