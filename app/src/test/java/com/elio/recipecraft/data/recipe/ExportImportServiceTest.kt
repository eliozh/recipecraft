package com.elio.recipecraft.data.recipe

import com.elio.recipecraft.domain.CalorieSource
import com.elio.recipecraft.domain.Calories
import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Language
import com.elio.recipecraft.domain.MealType
import com.elio.recipecraft.domain.Recipe
import com.elio.recipecraft.domain.Step
import com.elio.recipecraft.testing.FakeNutritionLookups
import com.elio.recipecraft.testing.FakeRecipeDao
import com.elio.recipecraft.testing.MutableClock
import com.elio.recipecraft.testing.PLACEHOLDER_KEY
import com.elio.recipecraft.testing.ingredient
import com.elio.recipecraft.testing.lookupOf
import com.elio.recipecraft.testing.testRecipe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportImportServiceTest {

    private val dao = FakeRecipeDao()
    private val nutrition = FakeNutritionLookups(listOf(lookupOf("egg" to 143.0, "tomato" to 18.0)))
    private val clock = MutableClock(Instant.ofEpochMilli(1_761_609_600_000))
    private val repository = RecipeRepository(dao, nutrition, UnconfinedTestDispatcher())
    private val service = ExportImportService(repository, clock, UnconfinedTestDispatcher())

    /** A zh dinner of a full-day set whose ingredients all match: a DATABASE total. */
    private val tomatoEggs = Recipe(
        id = "zh-1",
        name = "番茄炒蛋",
        mealType = MealType.DINNER,
        servings = 2,
        calories = Calories(null, null, CalorieSource.AI_ESTIMATE, 380),
        ingredients = listOf(
            Ingredient("鸡蛋", "egg", "3", "个", 150.0, 215),
            Ingredient("番茄", "tomato", "2", "个", 300.5, 54),
            Ingredient("盐", "salt", "", "适量", null, null),
        ),
        tools = listOf("炒锅", "锅铲"),
        steps = listOf(Step(1, "打散鸡蛋。", 1), Step(2, "翻炒出锅。", null)),
        language = Language.ZH,
        setId = "set-1",
        createdAt = 2_000,
        updatedAt = 2_500,
    )

    /** An en recipe with an unmatched ingredient, no meal type and no tools: an AI estimate. */
    private val oatmeal = Recipe(
        id = "en-1",
        name = "Oatmeal",
        mealType = null,
        servings = 1,
        calories = Calories(null, null, CalorieSource.AI_ESTIMATE, 150),
        ingredients = listOf(Ingredient("Rolled oats", "rolled oats", "1/2", "cup", 40.5, 150)),
        tools = emptyList(),
        steps = listOf(Step(1, "Simmer the oats.", 5)),
        language = Language.EN,
        setId = null,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    private val lunch = tomatoEggs.copy(id = "zh-2", name = "番茄蛋汤", mealType = MealType.LUNCH, createdAt = 2_000, updatedAt = 2_000)

    private suspend fun exportAll(ids: Collection<String>? = null): Pair<RecipeExportResult, String> {
        val out = ByteArrayOutputStream()
        val result = service.export(ids) { out }
        return result to out.toString(Charsets.UTF_8)
    }

    private suspend fun import(text: String) = import(text.toByteArray(Charsets.UTF_8))

    private suspend fun import(bytes: ByteArray) = service.import { ByteArrayInputStream(bytes) }

    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/export/$name")!!.readBytes()

    private suspend fun deleteAll() = repository.all().forEach { repository.delete(it.id) }

    @Test
    fun `export, delete everything and import restores identical recipes`() = runTest {
        repository.save(listOf(oatmeal, tomatoEggs, lunch))
        val before = repository.all()
        assertEquals(CalorieSource.DATABASE, before.single { it.id == "zh-1" }.calories.source)
        assertEquals(CalorieSource.AI_ESTIMATE, before.single { it.id == "en-1" }.calories.source)

        val (exported, json) = exportAll()
        assertEquals(RecipeExportResult.Exported(3), exported)
        deleteAll()
        assertTrue(repository.all().isEmpty())

        assertEquals(RecipeImportResult.Imported(imported = 3, duplicates = 0, invalid = 0), import(json))
        assertEquals(before, repository.all())
        assertEquals(before.map { it.toEntity() }.sortedBy { it.id }, dao.recipes.sortedBy { it.id })
    }

    @Test
    fun `the file writes every field of the documented format`() = runTest {
        repository.save(listOf(tomatoEggs))
        val (_, json) = exportAll()
        val root = Json.parseToJsonElement(json).jsonObject

        assertEquals(1, root.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(clock.millis(), root.getValue("exportedAt").jsonPrimitive.long)
        val saved = repository.all().single()
        val recipe = Json.decodeFromString(RecipeExport.serializer(), json).recipes.single()
        assertEquals(saved, recipe.toDomain())
        assertEquals(269, recipe.calories.total) // (150 g egg × 143 + 300.5 g tomato × 18) / 100, rounded
        assertEquals(380, recipe.calories.aiTotal)
    }

    @Test
    fun `the file holds exactly the documented keys, so no key or setting`() = runTest {
        repository.save(listOf(tomatoEggs, oatmeal))
        val (_, json) = exportAll()

        val keys = mutableSetOf<String>()
        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> keys += key; collect(value) }
                is JsonArray -> element.forEach(::collect)
                else -> Unit
            }
        }
        collect(Json.parseToJsonElement(json))
        assertEquals(
            setOf(
                "version", "exportedAt", "recipes",
                "id", "name", "mealType", "servings", "calories", "ingredients", "tools", "steps", "language", "setId",
                "createdAt", "updatedAt",
                "total", "perServing", "source", "aiTotal",
                "nameEn", "quantity", "unit", "grams", "aiKcal",
                "index", "text", "minutes",
            ),
            keys,
        )
        assertFalse(json.contains(PLACEHOLDER_KEY))
    }

    @Test
    fun `export of selected ids writes only those that are saved, in the order given`() = runTest {
        repository.save(listOf(oatmeal, tomatoEggs, lunch))
        val (result, json) = exportAll(listOf("en-1", "missing", "zh-1"))

        assertEquals(RecipeExportResult.Exported(2), result)
        assertEquals(listOf("en-1", "zh-1"), Json.decodeFromString(RecipeExport.serializer(), json).recipes.map { it.id })
    }

    @Test
    fun `an empty library exports a file with no recipes`() = runTest {
        val (result, json) = exportAll()
        assertEquals(RecipeExportResult.Exported(0), result)
        assertTrue(Json.decodeFromString(RecipeExport.serializer(), json).recipes.isEmpty())
    }

    @Test
    fun `export fails when the file cannot be written or the library read`() = runTest {
        repository.save(listOf(oatmeal))
        assertEquals(RecipeExportResult.Failed, service.export { throw IOException("gone") })
        assertEquals(RecipeExportResult.Failed, service.export { throw SecurityException("revoked") })
        dao.failReads = true
        assertEquals(RecipeExportResult.Failed, service.export { ByteArrayOutputStream() })
    }

    @Test
    fun `a hand-written version 1 file imports with calories worked out against local tables`() = runTest {
        val result = import(fixture("v1_two_recipes.json"))

        assertEquals(RecipeImportResult.Imported(imported = 2, duplicates = 0, invalid = 0), result)
        val zh = repository.saved(listOf("zh-1")).getValue("zh-1")
        // The file said AI_ESTIMATE; salt has no grams and no kcal, so the egg alone gives a database total.
        assertEquals(Calories(215, 108, CalorieSource.DATABASE, 380), zh.calories)
        assertEquals("set-1", zh.setId)
        assertEquals(Ingredient("盐", "salt", "", "适量", null, null), zh.ingredients[1])
        assertEquals(1_761_600_500_000, zh.updatedAt)
        val en = repository.saved(listOf("en-1")).getValue("en-1")
        assertEquals(null, en.mealType)
        assertEquals(40.5, en.ingredients.single().grams!!, 0.0)
        assertEquals(Calories(null, null, CalorieSource.AI_ESTIMATE, null), en.calories)
    }

    @Test
    fun `a file from a newer version is rejected and nothing is saved`() = runTest {
        val newer = """{"version": 2, "exportedAt": 0, "recipes": [], "somethingNew": {}}"""
        assertEquals(RecipeImportResult.Failed(RecipeImportError.NEWER_VERSION), import(newer))
        // Even when its recipes look readable.
        val recipes = Json.parseToJsonElement(fixture("v1_two_recipes.json").decodeToString()).jsonObject.getValue("recipes")
        assertEquals(RecipeImportResult.Failed(RecipeImportError.NEWER_VERSION), import("""{"version": 99, "recipes": $recipes}"""))
        assertTrue(dao.recipes.isEmpty())
    }

    @Test
    fun `malformed JSON and files that are not exports are rejected`() = runTest {
        val notExports = listOf(
            "",
            "{",
            "not json at all",
            "[]",
            "\"text\"",
            """{"recipes": []}""",
            """{"version": "1", "recipes": []}""",
            """{"version": 1.5, "recipes": []}""",
            """{"version": 0, "recipes": []}""",
            """{"version": 1}""",
            """{"version": 1, "recipes": {}}""",
        )
        notExports.forEach { text ->
            assertEquals(text, RecipeImportResult.Failed(RecipeImportError.NOT_AN_EXPORT), import(text))
        }
        assertEquals(
            RecipeImportResult.Failed(RecipeImportError.NOT_AN_EXPORT),
            import(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)), // `{`, invalid UTF-8, `}`
        )
        assertTrue(dao.recipes.isEmpty())
    }

    @Test
    fun `a leading byte order mark is accepted`() = runTest {
        val result = import(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + fixture("v1_two_recipes.json"))
        assertEquals(RecipeImportResult.Imported(2, 0, 0), result)
    }

    @Test
    fun `a recipe already in the library is skipped and the saved one is kept as it is`() = runTest {
        repository.save(listOf(tomatoEggs, oatmeal))
        val (_, json) = exportAll()
        // The saved oatmeal changed since the export; the export must not overwrite it.
        val changed = repository.all().single { it.id == "en-1" }.copy(name = "Overnight oats", updatedAt = 9_000)
        repository.restore(changed)
        repository.delete("zh-1")

        assertEquals(RecipeImportResult.Imported(imported = 1, duplicates = 1, invalid = 0), import(json))
        assertEquals(changed, repository.saved(listOf("en-1")).getValue("en-1"))
        assertEquals("番茄炒蛋", repository.saved(listOf("zh-1")).getValue("zh-1").name)

        // Importing the same file again adds nothing: both are identical to what is saved.
        assertEquals(RecipeImportResult.Imported(imported = 0, duplicates = 2, invalid = 0), import(json))
        assertEquals(2, dao.recipes.size)
    }

    @Test
    fun `a repeated id in the file keeps the first recipe`() = runTest {
        val first = oatmeal.toExported()
        val second = first.copy(name = "Second")
        val file = Json.encodeToString(RecipeExport.serializer(), RecipeExport(version = 1, exportedAt = 0, recipes = listOf(first, second)))

        assertEquals(RecipeImportResult.Imported(imported = 1, duplicates = 1, invalid = 0), import(file))
        assertEquals("Oatmeal", dao.recipes.single().name)
    }

    @Test
    fun `invalid recipes are skipped and counted, valid ones still import`() = runTest {
        val valid = Json.encodeToJsonElement(ExportedRecipe.serializer(), oatmeal.toExported()).jsonObject
        fun with(key: String, value: String) = JsonObject(valid + (key to Json.parseToJsonElement(value)))
        fun without(key: String) = JsonObject(valid - key)
        val entries = listOf(
            valid,
            with("name", "\"  \""),
            with("id", "\"\""),
            with("ingredients", "[]"),
            with("steps", "[]"),
            with("servings", "0"),
            with("mealType", "\"BRUNCH\""),
            with("language", "\"FR\""),
            with("calories", "{\"total\": 1}"),
            without("name"),
            without("ingredients"),
            Json.parseToJsonElement("\"a string\""),
            Json.parseToJsonElement("42"),
        )
        val file = """{"version": 1, "exportedAt": 0, "recipes": ${JsonArray(entries)}}"""

        assertEquals(RecipeImportResult.Imported(imported = 1, duplicates = 0, invalid = entries.size - 1), import(file))
        assertEquals(listOf("en-1"), dao.recipes.map { it.id })
    }

    @Test
    fun `unknown keys inside a version 1 file are ignored`() = runTest {
        val valid = Json.encodeToJsonElement(ExportedRecipe.serializer(), oatmeal.toExported()).jsonObject
        val file = """{"version": 1, "exportedAt": 0, "extra": true, "recipes": [${JsonObject(valid + ("note" to Json.parseToJsonElement("1")))}]}"""
        assertEquals(RecipeImportResult.Imported(1, 0, 0), import(file))
    }

    @Test
    fun `a file over the size cap is rejected`() = runTest {
        val small = ExportImportService(repository, clock, UnconfinedTestDispatcher(), maxBytes = 100)
        val bytes = fixture("v1_two_recipes.json")
        assertTrue(bytes.size > 100)
        assertEquals(RecipeImportResult.Failed(RecipeImportError.TOO_LARGE), small.import { ByteArrayInputStream(bytes) })
        assertEquals(20L * 1024 * 1024, ExportImportService.MAX_BYTES)
    }

    @Test
    fun `a file that cannot be read is reported`() = runTest {
        assertEquals(RecipeImportResult.Failed(RecipeImportError.UNREADABLE), service.import { throw IOException("gone") })
        assertEquals(RecipeImportResult.Failed(RecipeImportError.UNREADABLE), service.import { throw SecurityException("revoked") })
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("broken")
        }
        assertEquals(RecipeImportResult.Failed(RecipeImportError.UNREADABLE), service.import { failing })
    }

    @Test
    fun `a database that cannot be written is reported`() = runTest {
        dao.failWrites = true
        assertEquals(RecipeImportResult.Failed(RecipeImportError.SAVE_FAILED), import(fixture("v1_two_recipes.json")))
    }

    @Test
    fun `the suggested file name carries the date`() {
        assertEquals("recipecraft-2026-10-28.json", exportFileName(LocalDate.of(2026, 10, 28)))
    }

    @Test
    fun `a recipe imported without matching tables falls back to the model's estimate`() = runTest {
        nutrition.lookups = emptyList()
        val recipe = testRecipe("a", ingredients = listOf(ingredient("egg")), aiTotal = 300)
            .copy(calories = Calories(999, 999, CalorieSource.DATABASE, 300))
        val file = Json.encodeToString(RecipeExport.serializer(), RecipeExport(version = 1, exportedAt = 0, recipes = listOf(recipe.toExported())))

        import(file)
        assertEquals(Calories(300, 150, CalorieSource.AI_ESTIMATE, 300), dao.recipes.single().calories)
    }
}
