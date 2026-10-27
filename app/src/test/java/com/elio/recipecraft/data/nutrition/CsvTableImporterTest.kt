package com.elio.recipecraft.data.nutrition

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvTableImporterTest {

    private val importer = CsvTableImporter()

    private fun parse(csv: String, importer: CsvTableImporter = this.importer) = importer.parse(csv.byteInputStream())

    private fun success(csv: String) = parse(csv) as CsvParseResult.Success

    @Test
    fun `a good file imports every row`() {
        val result = success("name,aliases,kcal_per_100g\n鸡蛋,鸡子|蛋,144\n番茄,,15\nrolled oats, oats | porridge oats ,379.5\n")
        assertEquals(
            listOf(
                NutritionFood("鸡蛋", listOf("鸡子", "蛋"), 144.0),
                NutritionFood("番茄", emptyList(), 15.0),
                NutritionFood("rolled oats", listOf("oats", "porridge oats"), 379.5),
            ),
            result.foods,
        )
        assertEquals(0, result.skipped)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `the bundled chinese fixture parses with its bom and crlf line breaks`() {
        val stream = javaClass.getResourceAsStream("/fixtures/nutrition/zh_table.csv")!!
        val result = stream.use(importer::parse) as CsvParseResult.Success
        assertEquals(listOf("番茄", "鸡蛋", "食用油", "盐"), result.foods.map { it.name })
        assertEquals(0, result.skipped)
    }

    @Test
    fun `bad rows are skipped and counted, and the good rows import`() {
        val csv = """
            name,aliases,kcal_per_100g
            鸡蛋,,144
            ,蛋,144
            番茄,,abc
            黄油,,-5
            鸡汤,,2000
            豆腐,,NaN
            米饭,116
            牛奶,,54,extra
            菠菜,,23
        """.trimIndent()
        val result = success(csv)
        assertEquals(listOf("鸡蛋", "菠菜"), result.foods.map { it.name })
        assertEquals(7, result.skipped)
        assertEquals(
            listOf(
                RowProblem(3, RowError.MISSING_NAME),
                RowProblem(4, RowError.INVALID_KCAL),
                RowProblem(5, RowError.INVALID_KCAL),
                RowProblem(6, RowError.KCAL_TOO_HIGH),
                RowProblem(7, RowError.INVALID_KCAL),
            ),
            result.problems,
        )
    }

    @Test
    fun `only the first few problems are kept as examples`() {
        val csv = "name,aliases,kcal_per_100g\n" + (1..20).joinToString("\n") { ",,1" } + "\nok,,1"
        val result = success(csv)
        assertEquals(20, result.skipped)
        assertEquals(CsvTableImporter.MAX_PROBLEMS, result.problems.size)
    }

    @Test
    fun `wrong column counts are skipped`() {
        val result = success("name,aliases,kcal_per_100g\n米饭,116\n牛奶,,54,x\n菠菜,,23")
        assertEquals(listOf(RowProblem(2, RowError.WRONG_COLUMN_COUNT), RowProblem(3, RowError.WRONG_COLUMN_COUNT)), result.problems)
    }

    @Test
    fun `kcal at the limits is accepted`() {
        val result = success("name,aliases,kcal_per_100g\nwater,,0\nlard,,1000\noil,, 884 ")
        assertEquals(listOf(0.0, 1000.0, 884.0), result.foods.map { it.kcalPer100g })
    }

    @Test
    fun `a utf-8 bom is dropped`() {
        val result = success("﻿name,aliases,kcal_per_100g\n鸡蛋,,144")
        assertEquals("鸡蛋", result.foods.single().name)
    }

    @Test
    fun `the header tolerates case and surrounding whitespace`() {
        val result = success(" Name , ALIASES ,Kcal_Per_100g \r\n鸡蛋,,144\r\n")
        assertEquals(1, result.foods.size)
    }

    @Test
    fun `a wrong header fails the whole import`() {
        listOf(
            "name,kcal_per_100g\n鸡蛋,144",
            "名称,别名,热量\n鸡蛋,,144",
            "鸡蛋,,144\n番茄,,15",
            "name,aliases,kcal_per_100g,notes\n鸡蛋,,144,",
            "\"name,aliases,kcal_per_100g\n",
        ).forEach { csv ->
            assertEquals(csv, CsvParseResult.Failure(TableImportError.WRONG_HEADER), parse(csv))
        }
    }

    @Test
    fun `an empty file fails`() {
        assertEquals(CsvParseResult.Failure(TableImportError.EMPTY), parse(""))
        assertEquals(CsvParseResult.Failure(TableImportError.EMPTY), parse("﻿"))
    }

    @Test
    fun `a file with no valid rows fails and still reports what it skipped`() {
        assertEquals(CsvParseResult.Failure(TableImportError.NO_VALID_ROWS, skipped = 2), parse("name,aliases,kcal_per_100g\n,,1\nx,,y"))
        assertEquals(CsvParseResult.Failure(TableImportError.NO_VALID_ROWS), parse("name,aliases,kcal_per_100g\n"))
    }

    @Test
    fun `blank lines are ignored, not counted`() {
        val result = success("name,aliases,kcal_per_100g\n\n鸡蛋,,144\n   \n\n番茄,,15\n\n")
        assertEquals(2, result.foods.size)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `quoted fields keep commas, quotes and line breaks`() {
        val csv = "name,aliases,kcal_per_100g\n" +
            "\"Cheese, cheddar\",\"cheddar|\"\"sharp\"\" cheddar\",403\n" +
            "\"Bread\r\nwhite\",,\"265\"\n" +
            "\"\",,1\n" +
            "tofu,,76"
        val result = success(csv)
        assertEquals(
            listOf(
                NutritionFood("Cheese, cheddar", listOf("cheddar", "\"sharp\" cheddar"), 403.0),
                NutritionFood("Bread\nwhite", emptyList(), 265.0),
                NutritionFood("tofu", emptyList(), 76.0),
            ),
            result.foods,
        )
        // The quoted line break moves the empty name to line 5.
        assertEquals(listOf(RowProblem(5, RowError.MISSING_NAME)), result.problems)
    }

    @Test
    fun `broken quoting skips the row`() {
        val result = success("name,aliases,kcal_per_100g\n\"egg\"x,,143\nmilk,,61\n\"open,,1\nrice,,116")
        assertEquals(listOf("milk"), result.foods.map { it.name })
        assertEquals(listOf(RowProblem(2, RowError.BAD_QUOTES), RowProblem(4, RowError.BAD_QUOTES)), result.problems)
    }

    @Test
    fun `a bare quote inside an unquoted field is kept as text`() {
        assertEquals("5\" sub roll", success("name,aliases,kcal_per_100g\n5\" sub roll,,250").foods.single().name)
    }

    @Test
    fun `old mac line breaks work`() {
        assertEquals(2, success("name,aliases,kcal_per_100g\r鸡蛋,,144\r番茄,,15").foods.size)
    }

    @Test
    fun `duplicate names keep the first row and skip the rest`() {
        val result = success("name,aliases,kcal_per_100g\n鸡蛋,,144\nEgg,,143\n 鸡蛋 ,,150\negg,,140\nＥＧＧ,,1")
        assertEquals(listOf(NutritionFood("鸡蛋", emptyList(), 144.0), NutritionFood("Egg", emptyList(), 143.0)), result.foods)
        assertEquals(3, result.skipped)
        assertEquals(listOf(4, 5, 6), result.problems.map { it.row })
        assertTrue(result.problems.all { it.error == RowError.DUPLICATE_NAME })
    }

    @Test
    fun `repeated aliases collapse`() {
        assertEquals(listOf("a", "b"), success("name,aliases,kcal_per_100g\nx,a|b|a||,1").foods.single().aliases)
    }

    @Test
    fun `a file over the size cap fails`() {
        val small = CsvTableImporter(maxBytes = 64)
        val csv = "name,aliases,kcal_per_100g\n" + (1..10).joinToString("\n") { "food$it,,$it" }
        assertEquals(CsvParseResult.Failure(TableImportError.TOO_LARGE), parse(csv, small))
        assertTrue(parse("name,aliases,kcal_per_100g\negg,,1", small) is CsvParseResult.Success)
    }

    @Test
    fun `the default cap is five megabytes and streams without loading the file`() {
        assertEquals(5L * 1024 * 1024, CsvTableImporter.MAX_BYTES)
        val header = "name,aliases,kcal_per_100g\n".toByteArray()
        var served = 0L
        // An endless stream of rows: the cap must stop it, rather than reading it all first.
        val endless = object : InputStream() {
            private val row = "food,,1\n".toByteArray()
            override fun read(): Int {
                val i = served++
                return if (i < header.size) header[i.toInt()].toInt() else row[((i - header.size) % row.size).toInt()].toInt()
            }
        }
        assertEquals(CsvParseResult.Failure(TableImportError.TOO_LARGE), importer.parse(endless))
        assertTrue(served <= CsvTableImporter.MAX_BYTES + 64 * 1024)
    }

    @Test
    fun `text that is not utf-8 fails clearly`() {
        val gbk = "name,aliases,kcal_per_100g\n鸡蛋,,144".toByteArray(charset("GBK"))
        assertEquals(CsvParseResult.Failure(TableImportError.NOT_UTF8), importer.parse(ByteArrayInputStream(gbk)))
    }

    @Test(expected = IOException::class)
    fun `a failing stream throws`() {
        importer.parse(
            object : InputStream() {
                override fun read(): Int = throw IOException("gone")
            },
        )
    }
}
