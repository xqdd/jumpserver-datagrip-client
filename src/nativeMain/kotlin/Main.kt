import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.getenv

val client = HttpClient(WinHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 100
        requestTimeoutMillis = 500
        socketTimeoutMillis = 500
    }
}
val maxRetries = 10
val portEnvKey = "IDEA_PORTS"
val minPort = 1
val maxPort = 65535


fun main(args: Array<String>) = runBlocking {
    val basePorts = readBasePorts()
    for (i in 0..maxRetries) {
        for (port in basePorts) {
            launch { run(args, port + i) }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readBasePorts(): List<Int> {
    val defaultPorts = listOf(63342, 54640, 52570)
    val envPorts = getenv(portEnvKey)
        ?.toKString()
        ?.split(",")
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.filter { it in minPort..maxPort }
        ?.toList()
        .orEmpty()
    return (defaultPorts + envPorts).distinct()
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
