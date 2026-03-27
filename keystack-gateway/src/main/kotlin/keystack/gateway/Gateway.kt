package keystack.gateway

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import org.slf4j.LoggerFactory

class Gateway(
    private val host: String = "0.0.0.0",
    private val port: Int = 4566
) {
    private val logger = LoggerFactory.getLogger(Gateway::class.java)

    private val handlerChain = HandlerChain(
        requestHandlers = listOf(
            keystack.gateway.handlers.ServiceDetectionHandler(),
            keystack.gateway.handlers.RequestParserHandler()
        ),
        responseHandlers = listOf(
            keystack.gateway.handlers.ResponseSerializerHandler()
        ),
        exceptionHandlers = listOf(
            { context, exception ->
                logger.error("Error handling request ${context.requestId}", exception)
                val status = if (exception is ServiceException) {
                    HttpStatusCode.fromValue(exception.statusCode)
                } else {
                    HttpStatusCode.InternalServerError
                }
                
                if (!context.request.call.response.isCommitted) {
                    context.request.call.respondText(
                        exception.message ?: "Internal Error",
                        status = status
                    )
                }
            }
        )
    )

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start(wait: Boolean = true) {
        server = embeddedServer(CIO, port = port, host = host) {
            module()
        }.start(wait = wait)
    }

    fun Application.module() {
        install(ContentNegotiation) {
            json()
        }
        configureRouting()
    }

    fun stop() {
        server?.stop(1000, 2000)
    }

    private fun Application.configureRouting() {
        routing {
            get("/_keystack/health") {
                call.respond(mapOf("status" to "running"))
            }

            route("{...}") {
                handle {
                    val context = RequestContext(call.request)
                    try {
                        handlerChain.handle(context)

                        if (!call.response.isCommitted) {
                            when {
                                context.serviceResponse != null -> call.respond(context.serviceResponse!!)
                                context.serviceException != null -> {
                                    val ex = context.serviceException!!
                                    call.respondText(ex.message, status = HttpStatusCode.fromValue(ex.statusCode))
                                }
                                else -> call.respond(HttpStatusCode.NotFound, "Not Found")
                            }
                        }
                    } catch (e: Throwable) {
                        logger.error("Chain execution failed", e)
                        if (!call.response.isCommitted) {
                            call.respond(HttpStatusCode.InternalServerError, "Internal Server Error")
                        }
                    }
                }
            }
        }
    }
}
