package keystack.state

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Utility for saving and loading state snapshots.
 */
object StateSnapshot {
    val mapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    /**
     * Implementation of [StateVisitor] that saves service state to disk.
     */
    class SnapshotSaver(private val dataDir: Path) : StateVisitor {
        override fun visit(store: AccountRegionStore<out ServiceStore>) {
            store.forEach { accountId, region, s ->
                val serviceDir = dataDir.resolve(store.serviceName).resolve(accountId)
                serviceDir.createDirectories()
                val filePath = serviceDir.resolve("$region.json")
                
                Files.writeString(filePath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(s))
            }
        }
    }

    /**
     * Implementation of [StateVisitor] that loads service state from disk.
     */
    class SnapshotLoader(private val dataDir: Path) : StateVisitor {
        override fun visit(store: AccountRegionStore<out ServiceStore>) {
            val serviceDir = dataDir.resolve(store.serviceName)
            if (!serviceDir.exists()) return

            serviceDir.listDirectoryEntries().forEach { accountDir ->
                if (accountDir.isDirectory()) {
                    val accountId = accountDir.name
                    accountDir.listDirectoryEntries("*.json").forEach { filePath ->
                        val region = filePath.nameWithoutExtension
                        
                        // We need a way to load into the existing store instance
                        val existingStore = store[accountId, region]
                        
                        try {
                            val content = Files.readString(filePath)
                            // Update the existing store instance with data from JSON
                            // This is tricky with Jackson if we want to update an existing object.
                            // We can use readerForUpdating.
                            mapper.readerForUpdating(existingStore).readValue<ServiceStore>(content)
                        } catch (e: Exception) {
                            // Log error or handle it
                            System.err.println("Failed to load state for ${store.serviceName} $accountId $region: ${e.message}")
                        }
                    }
                }
            }
        }
    }
}
