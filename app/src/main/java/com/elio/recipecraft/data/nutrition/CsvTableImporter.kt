package com.elio.recipecraft.data.nutrition

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.inject.Inject

/** Why a whole file was rejected; nothing is imported. */
enum class TableImportError {
    /** The file has no header row. */
    EMPTY,

    /** The first row is not `name,aliases,kcal_per_100g`. */
    WRONG_HEADER,

    /** The file is over [CsvTableImporter.MAX_BYTES]. */
    TOO_LARGE,

    /** The bytes are not valid UTF-8, such as a CSV saved as GBK. */
    NOT_UTF8,

    /** Every data row was invalid, so there is nothing to import. */
    NO_VALID_ROWS,

    /** The file could not be opened or read. */
    UNREADABLE,

    /** The parsed table could not be written to the database. */
    SAVE_FAILED,
}

/** Why one row was skipped. */
enum class RowError {
    WRONG_COLUMN_COUNT,
    MISSING_NAME,
    INVALID_KCAL,
    KCAL_TOO_HIGH,
    DUPLICATE_NAME,

    /** A quoted field that never closes, or text right after a closing quote. */
    BAD_QUOTES,
}

/** [row] is the file's line number where the row starts; the header is row 1. */
data class RowProblem(val row: Int, val error: RowError)

sealed interface CsvParseResult {
    /** [problems] holds the first few skipped rows, as examples for the summary; [skipped] counts them all. */
    data class Success(val foods: List<NutritionFood>, val skipped: Int, val problems: List<RowProblem>) : CsvParseResult

    data class Failure(val error: TableImportError, val skipped: Int = 0) : CsvParseResult
}

/**
 * Reads a user's nutrition table from CSV: UTF-8 (a leading BOM is dropped), RFC 4180 quoting, and
 * the header `name,aliases,kcal_per_100g`. `aliases` is an optional `|`-separated list.
 *
 * Invalid rows are skipped and counted, and so is every repeat of a name already seen in the file
 * (the first one wins). The file streams through a small buffer and stops at [maxBytes], so a huge
 * file never sits in memory. Row contents never leave this class: problems carry only a row number.
 */
class CsvTableImporter(private val maxBytes: Long) {

    @Inject
    constructor() : this(MAX_BYTES)

    /** Reads [input] to the end without closing it. Throws [IOException] when the stream itself fails. */
    fun parse(input: InputStream): CsvParseResult {
        val capped = CappedInputStream(input, maxBytes)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            parse(CsvRecordReader(InputStreamReader(capped, decoder).buffered()))
        } catch (_: FileTooLargeException) {
            CsvParseResult.Failure(TableImportError.TOO_LARGE)
        } catch (_: CharacterCodingException) {
            CsvParseResult.Failure(TableImportError.NOT_UTF8)
        }
    }

    private fun parse(reader: CsvRecordReader): CsvParseResult {
        val header = reader.next() ?: return CsvParseResult.Failure(TableImportError.EMPTY)
        if (header.malformed || header.fields.map { it.trim().lowercase() } != HEADER) {
            return CsvParseResult.Failure(TableImportError.WRONG_HEADER)
        }

        val foods = mutableListOf<NutritionFood>()
        val seen = HashSet<String>()
        val problems = mutableListOf<RowProblem>()
        var skipped = 0
        while (true) {
            val record = reader.next() ?: break
            if (record.isBlank) continue
            val result = validate(record)
            val error = when {
                result is RowResult.Invalid -> result.error
                !seen.add(IngredientMatcher.normalize((result as RowResult.Valid).food.name)) -> RowError.DUPLICATE_NAME
                else -> {
                    foods += result.food
                    null
                }
            }
            if (error != null) {
                skipped++
                if (problems.size < MAX_PROBLEMS) problems += RowProblem(record.line, error)
            }
        }
        if (foods.isEmpty()) return CsvParseResult.Failure(TableImportError.NO_VALID_ROWS, skipped)
        return CsvParseResult.Success(foods, skipped, problems)
    }

    private sealed interface RowResult {
        data class Valid(val food: NutritionFood) : RowResult
        data class Invalid(val error: RowError) : RowResult
    }

    private fun validate(record: CsvRecord): RowResult {
        if (record.malformed) return RowResult.Invalid(RowError.BAD_QUOTES)
        if (record.fields.size != HEADER.size) return RowResult.Invalid(RowError.WRONG_COLUMN_COUNT)
        val (rawName, rawAliases, rawKcal) = record.fields
        val name = rawName.trim()
        if (name.isEmpty()) return RowResult.Invalid(RowError.MISSING_NAME)
        val kcal = rawKcal.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            ?: return RowResult.Invalid(RowError.INVALID_KCAL)
        if (kcal > MAX_KCAL_PER_100G) return RowResult.Invalid(RowError.KCAL_TOO_HIGH)
        val aliases = rawAliases.split('|').map(String::trim).filter(String::isNotEmpty).distinct()
        return RowResult.Valid(NutritionFood(name, aliases, kcal))
    }

    companion object {
        /** 5 MB holds about a hundred thousand rows, far more than any real food table. */
        const val MAX_BYTES = 5L * 1024 * 1024

        /** Pure fat is 900 kcal per 100 g, so anything above this is a typo or the wrong unit (kJ). */
        const val MAX_KCAL_PER_100G = 1000.0

        const val MAX_PROBLEMS = 5

        private val HEADER = listOf("name", "aliases", "kcal_per_100g")
    }
}

private class FileTooLargeException : IOException()

/** Fails with [FileTooLargeException] as soon as more than [maxBytes] have been read. */
private class CappedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) count(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it) }

    private fun count(n: Number) {
        count += n.toLong()
        if (count > maxBytes) throw FileTooLargeException()
    }
}

/** One CSV record; [line] is where it starts. */
internal class CsvRecord(val fields: List<String>, val line: Int, val malformed: Boolean) {
    val isBlank: Boolean get() = !malformed && fields.size == 1 && fields[0].isBlank()
}

/**
 * Splits RFC 4180 CSV into records one at a time. Line breaks are CRLF, LF or CR, and a quoted
 * field may hold commas, doubled quotes and line breaks. A leading BOM is dropped.
 */
internal class CsvRecordReader(private val reader: Reader) {
    private var line = 1
    private var started = false
    private var pending = NONE

    fun next(): CsvRecord? {
        if (!started) {
            started = true
            val first = reader.read()
            if (first != BOM && first != EOF) pending = first
        }
        var c = read()
        if (c == EOF) return null

        val start = line
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var malformed = false
        while (true) {
            if (c == '"'.code && field.isEmpty()) {
                // A quoted field: read up to the closing quote, then expect a comma or the end of the record.
                while (true) {
                    c = read()
                    when {
                        c == EOF -> return CsvRecord(fields + field.toString(), start, malformed = true)
                        c == '"'.code -> {
                            c = read()
                            if (c == '"'.code) field.append('"') else break
                        }
                        c == '\n'.code || c == '\r'.code -> {
                            countLineBreak(c)
                            field.append('\n')
                        }
                        else -> field.append(c.toChar())
                    }
                }
                if (c != ','.code && c != '\n'.code && c != '\r'.code && c != EOF) {
                    malformed = true
                    while (c != ','.code && c != '\n'.code && c != '\r'.code && c != EOF) c = read()
                }
            } else {
                while (c != ','.code && c != '\n'.code && c != '\r'.code && c != EOF) {
                    field.append(c.toChar())
                    c = read()
                }
            }
            fields += field.toString()
            field.clear()
            when (c) {
                ','.code -> c = read()
                EOF -> return CsvRecord(fields, start, malformed)
                else -> {
                    countLineBreak(c)
                    return CsvRecord(fields, start, malformed)
                }
            }
        }
    }

    private fun read(): Int {
        if (pending != NONE) return pending.also { pending = NONE }
        return reader.read()
    }

    /** Counts a line break, consuming the LF of a CRLF pair. */
    private fun countLineBreak(c: Int) {
        line++
        if (c == '\r'.code) {
            val after = reader.read()
            if (after != '\n'.code) pending = after
        }
    }

    private companion object {
        const val EOF = -1
        const val NONE = -2
        const val BOM = 0xFEFF
    }
}
