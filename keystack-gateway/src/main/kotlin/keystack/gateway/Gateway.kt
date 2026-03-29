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
import io.ktor.server.plugins.doublereceive.DoubleReceive
import org.slf4j.LoggerFactory

class Gateway(
    private val host: String = "0.0.0.0",
    private val port: Int = 4566
) {
    private val logger = LoggerFactory.getLogger(Gateway::class.java)
    
    private val serviceRegistry = keystack.provider.ServiceRegistry()

    private val requestHandlers = listOf(
        keystack.gateway.handlers.ServiceDetectionHandler(),
        keystack.gateway.handlers.RequestParserHandler(),
        keystack.provider.ServiceRequestRouter(serviceRegistry)
    )
    
    private val responseHandlers = listOf(
        keystack.gateway.handlers.ResponseSerializerHandler()
    )
    
    private val exceptionHandlers: List<ExceptionHandler> = listOf(
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

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start(wait: Boolean = true) {
        server = embeddedServer(CIO, port = port, host = host) {
            module()
        }.start(wait = wait)
    }

    fun Application.module() {
        install(DoubleReceive)
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

            post("/_keystack/state/reset") {
                serviceRegistry.resetAll()
                call.respond(HttpStatusCode.OK, mapOf("status" to "reset"))
            }

            route("/_keystack_lambda/{executorId}") {
                post("/invocations/{requestId}/response") {
                    val executorId = call.parameters["executorId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val requestId = call.parameters["requestId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val payload = call.receive<ByteArray>()
                    keystack.services.lambda.execution.ExecutorRouter.getEndpoint(executorId)?.onInvocationResponse(requestId, payload, null)
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/invocations/{requestId}/error") {
                    val executorId = call.parameters["executorId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val requestId = call.parameters["requestId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val payload = call.receive<ByteArray>()
                    keystack.services.lambda.execution.ExecutorRouter.getEndpoint(executorId)?.onInvocationError(requestId, payload, null)
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/invocations/{requestId}/logs") {
                    val executorId = call.parameters["executorId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val requestId = call.parameters["requestId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val payload = call.receiveText()
                    keystack.services.lambda.execution.ExecutorRouter.getEndpoint(executorId)?.onLogs(requestId, payload)
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/status/{statusId}/ready") {
                    val executorId = call.parameters["executorId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    keystack.services.lambda.execution.ExecutorRouter.getEndpoint(executorId)?.onStatusReady()
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/status/{statusId}/error") {
                    val executorId = call.parameters["executorId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val error = try { call.receiveText() } catch (e: Exception) { "" }
                    keystack.services.lambda.execution.ExecutorRouter.getEndpoint(executorId)?.onStatusError(error)
                    call.respond(HttpStatusCode.Accepted)
                }
            }

            route("{...}") {
                handle {
                    val context = RequestContext(call.request)
                    val chain = HandlerChain(requestHandlers, responseHandlers, exceptionHandlers)
                    try {
                        chain.handle(context)

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
