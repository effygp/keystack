package keystack.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import keystack.gateway.server.EmbeddedServer
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("keystack.cli")

class KeystackCommand : CliktCommand(name = "keystack") {
    override fun run() = Unit
}

class StartCommand : CliktCommand(name = "start", help = "Start the Keystack emulator") {
    private val port by option("-p", "--port", help = "Port to listen on").int().default(4566)
    private val host by option("-h", "--host", help = "Host to listen on").default("0.0.0.0")

    override fun run() {
        logger.info("Starting Keystack on {}:{}...", host, port)
        keystack.provider.initKeystack()
        EmbeddedServer.main(arrayOf())
    }
}

fun main(args: Array<String>) = KeystackCommand()
    .subcommands(StartCommand())
    .main(args)
