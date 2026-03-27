package keystack.gateway.server

import keystack.gateway.Gateway
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * JVM entry point for the Keystack gateway.
 */
class EmbeddedServer(
    private val host: String = "0.0.0.0",
    private val port: Int = 4566
) {
    private val logger = LoggerFactory.getLogger(EmbeddedServer::class.java)
    private val gateway = Gateway(host, port)

    fun run() {
        logger.info("Starting Keystack gateway on $host:$port...")
        
        Runtime.getRuntime().addShutdownHook(Thread {
            shutdown()
        })

        try {
            println("Ready.")
            gateway.start(wait = true)
        } catch (e: Exception) {
            logger.error("Failed to start gateway", e)
            exitProcess(1)
        }
    }

    private fun shutdown() {
        logger.info("Shutting down...")
        gateway.stop()
    }
}

fun main() {
    val hostPort = System.getenv("NIMBUS_GATEWAY_LISTEN") ?: "0.0.0.0:4566"
    val host = hostPort.substringBefore(":")
    val port = hostPort.substringAfter(":", "4566").toInt()
    EmbeddedServer(host, port).run()
}
