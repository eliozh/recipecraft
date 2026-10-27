package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.di.IoDispatcher
import com.elio.recipecraft.domain.Recipe
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Clock
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

sealed interface RecipeExportResult {
    data class Exported(val count: Int) : RecipeExportResult

    /** The file could not be written, or the library could not be read. */
    data object Failed : RecipeExportResult
}

/** Why a whole file was rejected; nothing is imported. */
enum class RecipeImportError {
    /** The file is over [ExportImportService.MAX_BYTES]. */
    TOO_LARGE,

    /** The file is not UTF-8 JSON in the [RecipeExport] shape, or has no valid `version`. */
    NOT_AN_EXPORT,

    /** The file's `version` is newer than [RecipeExport.VERSION]; a newer app wrote it. */
    NEWER_VERSION,

    /** The file could not be opened or read. */
    UNREADABLE,

    /** The recipes could not be written to the database. */
    SAVE_FAILED,
}

sealed interface RecipeImportResult {
    /**
     * [imported] recipes were added. [duplicates] were skipped because a recipe with their id is
     * already in the library or came earlier in the file. [invalid] were skipped because they could
     * not be read or lack a name, ingredients or steps.
     */
    data class Imported(val imported: Int, val duplicates: Int, val invalid: Int) : RecipeImportResult

    data class Failed(val error: RecipeImportError) : RecipeImportResult
}

/**
 * Writes saved recipes to a [RecipeExport] file and reads them back.
 *
 * Import rules:
 * - A file whose `version` is newer than [RecipeExport.VERSION] is rejected as a whole, and so is a
 *   file that is not an export at all or is larger than [MAX_BYTES].
 * - Each recipe is read on its own: one that cannot be read, or has a blank id or name, no
 *   ingredients, no steps or fewer than one serving, is skipped and counted as invalid.
 * - Dedupe by id, with no overwrite: a recipe whose id is already in the library is skipped and the
 *   saved one stays exactly as it is, whether or not the two are identical. The same goes for a
 *   second recipe with an id seen earlier in the file.
 * - Imported recipes get their calories worked out again against this device's tables; the
 *   model's own estimate (`aiTotal`) is kept.
 *
 * Recipe contents are never logged.
 */
class ExportImportService(
    private val repository: RecipeRepository,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
    private val maxBytes: Long,
) {

    @Inject
    constructor(repository: RecipeRepository, clock: Clock, @IoDispatcher ioDispatcher: CoroutineDispatcher) :
        this(repository, clock, ioDispatcher, MAX_BYTES)

    /** Writes every saved recipe, or only those of [ids] that are saved, to the stream that [open] returns. */
    suspend fun export(ids: Collection<String>? = null, open: () -> OutputStream): RecipeExportResult =
        withContext(ioDispatcher) {
            try {
                val recipes = if (ids == null) repository.all() else repository.saved(ids).let { saved -> ids.mapNotNull { saved[it] } }
                val export = RecipeExport(version = RecipeExport.VERSION, exportedAt = clock.millis(), recipes = recipes.map { it.toExported() })
                val bytes = json.encodeToString(export).toByteArray(Charsets.UTF_8)
                open().use { it.write(bytes) }
                RecipeExportResult.Exported(recipes.size)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                RecipeExportResult.Failed
            } catch (_: SecurityException) {
                // The document provider revoked access.
                RecipeExportResult.Failed
            } catch (_: RuntimeException) {
                // Room reports an unreadable database as a runtime exception.
                RecipeExportResult.Failed
            }
        }

    /** Reads the export that [open] returns and adds its recipes to the library, following the rules above. */
    suspend fun import(open: () -> InputStream): RecipeImportResult = withContext(ioDispatcher) {
        val text = try {
            open().use { readCapped(it) }
        } catch (_: FileTooLargeException) {
            return@withContext RecipeImportResult.Failed(RecipeImportError.TOO_LARGE)
        } catch (_: CharacterCodingException) {
            return@withContext RecipeImportResult.Failed(RecipeImportError.NOT_AN_EXPORT)
        } catch (_: IOException) {
            return@withContext RecipeImportResult.Failed(RecipeImportError.UNREADABLE)
        } catch (_: SecurityException) {
            return@withContext RecipeImportResult.Failed(RecipeImportError.UNREADABLE)
        }
        val parsed = when (val read = parse(text)) {
            is Parsed.Failure -> return@withContext RecipeImportResult.Failed(read.error)
            is Parsed.Success -> read
        }
        try {
            val added = repository.insertNew(parsed.recipes)
            RecipeImportResult.Imported(
                imported = added,
                duplicates = parsed.duplicates + parsed.recipes.size - added,
                invalid = parsed.invalid,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            RecipeImportResult.Failed(RecipeImportError.SAVE_FAILED)
        }
    }

    private sealed interface Parsed {
        data class Success(val recipes: List<Recipe>, val duplicates: Int, val invalid: Int) : Parsed
        data class Failure(val error: RecipeImportError) : Parsed
    }

    private fun parse(text: String): Parsed {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return Parsed.Failure(RecipeImportError.NOT_AN_EXPORT)
        val version = (root["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val entries = root["recipes"] as? JsonArray
        return when {
            version == null || version < 1 -> Parsed.Failure(RecipeImportError.NOT_AN_EXPORT)
            version > RecipeExport.VERSION -> Parsed.Failure(RecipeImportError.NEWER_VERSION)
            entries == null -> Parsed.Failure(RecipeImportError.NOT_AN_EXPORT)
            else -> {
                val ids = mutableSetOf<String>()
                var duplicates = 0
                var invalid = 0
                val recipes = entries.mapNotNull { entry ->
                    val recipe = decode(entry)
                    when {
                        recipe == null -> null.also { invalid++ }
                        !ids.add(recipe.id) -> null.also { duplicates++ }
                        else -> recipe
                    }
                }
                Parsed.Success(recipes, duplicates, invalid)
            }
        }
    }

    /** The recipe [entry] holds, or null when it cannot be read or is incomplete. */
    private fun decode(entry: JsonElement): Recipe? {
        val recipe = try {
            json.decodeFromJsonElement(ExportedRecipe.serializer(), entry).toDomain()
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val complete = recipe.id.isNotBlank() &&
            recipe.name.isNotBlank() &&
            recipe.servings >= 1 &&
            recipe.ingredients.isNotEmpty() &&
            recipe.steps.isNotEmpty()
        return recipe.takeIf { complete }
    }

    /** Reads [input] as strict UTF-8, failing with [FileTooLargeException] past [maxBytes]. */
    private fun readCapped(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buffer.write(chunk, 0, n)
            if (buffer.size() > maxBytes) throw FileTooLargeException()
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(buffer.toByteArray())).toString().removePrefix(BOM)
    }

    private class FileTooLargeException : IOException()

    companion object {
        /** 20 MB holds many thousands of recipes; anything bigger is not an export from this app. */
        const val MAX_BYTES = 20L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private const val BOM = "\uFEFF"

        private val json = Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}
