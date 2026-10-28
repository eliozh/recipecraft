# RecipeCraft: Implementation Plan

The approved requirements are in `SPEC.md` (approved 2026-10-27), which is the source of truth for *what* to build. This file covers *how* and *in what order*.

## Context

RecipeCraft is a new Android app: free, personal and non-commercial. The user chats with an LLM to generate recipes for breakfast, lunch, dinner, and other meals, either as a full-day set or as single meals, and chooses how many to generate. They can ask the AI to change one recipe at a time, and they save the recipes they like. Each recipe has ingredients, kitchen tools, numbered steps, and calories. Calories come from a nutrition database when every ingredient matches one, and from an AI estimate otherwise. The user brings their own API key for Claude, OpenAI, DeepSeek, or a custom endpoint. The UI and the built-in prompts come in English and Chinese. Each recipe keeps the language it was generated in, and the app offers an LLM translation when the user switches language. The app targets Android 17 (API 37), lays out correctly on edge-to-edge and full-screen displays, and is tuned for the Samsung Z Flip 8 cover screen, which will be verified on a real device.

The project starts from nothing: `/home/elio/Workspace/recipecraft` held only planning documents. The git repo (branch `main`) was initialized before T1 so the planning documents could be committed first. The machine has an Android SDK at `~/Android/Sdk` with platforms `android-37.0`, build-tools `36.0.0`, system images 36.1 and 37.1, and a `Pixel_10_Pro` AVD on the 36.1 image. T1 added `Pixel_10_Pro_API37` on the 37.1 image, which every emulator check uses. **No JDK is on PATH**; the user chose a user-local Temurin 21 at `~/.jdks/jdk-21.0.13+9`, referenced from `~/.gradle/gradle.properties` (`org.gradle.java.home`, which picks the daemon's JVM) and never committed. The `gradlew` script itself still needs `JAVA_HOME` to start, so every command runs with `export JAVA_HOME=$HOME/.jdks/jdk-21.0.13+9` (see `AGENTS.md`).

Decisions the user confirmed:
- **App ID:** `com.elio.recipecraft`, with minSdk 34 (Android 14).
- **Storage:** recipes stay on the device and can be exported/imported.
- **Streaming:** AI replies stream token by token.
- **OpenAI model:** no default; the user fetches the model list on setup.
- **Calories:** the USDA database is bundled, the user can import their own CSV tables, and the AI estimate is the fallback. No Chinese table is shipped, because the China CDC table isn't openly licensed.
- **Recipe language:** fixed when the recipe is generated, with translation offered on a language switch.
- **Coverage:** Kover gate of 80% on `domain` and `data`.
- **Test device:** a real Z Flip 8 for the cover screen.

---

## Architecture Decisions

- **Stack:** Kotlin, Jetpack Compose with Material 3, a single activity, Navigation Compose, Hilt for dependency injection, Room for storage, DataStore for preferences, kotlinx.serialization for JSON, OkHttp with okhttp-sse for streaming, and Kover for coverage. These are the standard modern Android choices and cover everything the app needs.
- **SDK levels:** `compileSdk = 37`, `targetSdk = 37`, `minSdk = 34`. Targeting 35 or higher makes edge-to-edge mandatory, and on Android 16+ large screens ignore orientation and resizability locks, so the UI must be adaptive from the first task.
- **Modules:** one `:app` module with packages `data/llm`, `data/recipe`, `data/chat`, `data/nutrition`, `data/settings`, `domain`, `ui/chat`, `ui/library`, `ui/recipe`, `ui/settings`, and `ui/cover`. Splitting into more modules would be premature for an app this size.
- **One LLM interface, two wire protocols:**
  - `LlmClient.stream(request): Flow<LlmEvent>`, where an event is `Delta(text)`, `Done(usage)`, or `Error(kind)`.
  - `AnthropicClient` calls `/v1/messages` with SSE `content_block_delta` events and the `x-api-key` and `anthropic-version` headers.
  - `OpenAiCompatibleClient` calls `/chat/completions` with `stream: true` and SSE `choices[].delta.content`. It serves OpenAI, DeepSeek (`https://api.deepseek.com`), and custom endpoints.
  - A custom endpoint is a base URL, a protocol choice (OpenAI-compatible or Anthropic-compatible), a model, and a key.
- **Model choice:** models are set per provider, with defaults kept in `ProviderDefaults.kt` (Claude `claude-sonnet-5-5`, DeepSeek `deepseek-chat`, OpenAI none), plus a **"Fetch models"** button that calls each provider's `/v1/models`. An empty model field blocks sending until the user picks one.
- **Key security:** API keys are encrypted with Tink AEAD using an Android Keystore master key and stored in DataStore. They are never logged and never exported, and the app sets `android:allowBackup` / data-extraction rules to exclude them.
- **Structured output from streamed text:** the prompt asks the model for a short sentence of prose, then one fenced `json` block that follows the `RecipeBatch` schema. While the reply streams, the chat shows the prose and a "building recipes…" placeholder. When the stream ends, `RecipeParser` pulls out the JSON and decodes it leniently (`ignoreUnknownKeys`, `coerceInputValues`). If parsing fails, the app sends one automatic repair turn ("return only valid JSON for the schema"), and if that also fails it shows the raw text with a retry button. This works the same way with all providers, unlike provider-specific tool calling.
- **Domain model:**
  - `Recipe(id, name, mealType, servings, calories: Calories, ingredients, tools: List<String>, steps: List<Step(index, text, minutes?)>, language, setId?, createdAt, updatedAt)`
  - `Ingredient(name, nameEn, quantity, unit, grams?, aiKcal?)`. `name` is in the recipe's language. `nameEn` is a hidden match key: a simple, singular, lowercase common name such as `egg`, `whole milk` or `rolled oats`.
  - `Calories(total, perServing, source: DATABASE | AI_ESTIMATE, aiTotal)`. The AI's own figure is always kept, so the app can fall back to it.
  - `MealType`: BREAKFAST, LUNCH, DINNER, SNACK, DESSERT. `Language`: EN, ZH.
  - `RecipeBatch(recipes: List<Recipe>)`. A "full-day set" is one batch with a breakfast, a lunch, and a dinner that share a `setId`.
- **Calories pipeline:** `CalorieCalculator(recipe, tables) → Calories`.
  - For each ingredient, it tries the imported tables in priority order and then USDA.
  - **Lookup key:** `name` for `zh` recipes against imported tables, and `nameEn` for every recipe against USDA and against imported tables for `en` recipes.
  - **Normalized exact match:** case and width folding, trimmed, plus simple plural stripping for English. It checks the name first and then its aliases. There is no fuzzy matching, because a wrong match is worse than an honest AI estimate.
  - If every ingredient with `grams` matches, the total is Σ grams × kcal/100 and the source is DATABASE. Otherwise the recipe uses `aiTotal` and the source is AI_ESTIMATE.
  - The calculator runs whenever a recipe is parsed, modified, translated or imported, and whenever a table is imported or deleted.
- **Nutrition storage:**
  - **USDA:** a separate read-only Room database, `usda.db`, opened through `createFromAsset("nutrition/usda.db")`, so user-data migrations never touch it. A script in `tools/nutrition/` builds it from FDC Foundation + SR Legacy plus a hand-curated `aliases.csv` that maps the roughly 500 most common ingredient names to FDC ids, with only name, aliases and kcal per 100 g, so it stays under 1 MB. The built asset and the script are both committed, together with a `NOTICE`.
  - **User-imported tables:** these live in the main `AppDatabase`, in the tables `nutrition_tables(id, name, priority, importedAt)` and `nutrition_foods(tableId, name, aliases, kcalPer100g)`. They are never exported.
- **Room (`AppDatabase`):** the `recipes` table stores the list fields through TypeConverters as JSON columns. There are also `conversations` and `messages` tables so a chat survives process death and fold/unfold, plus the nutrition tables above. Schemas are exported to `app/schemas/` for migration tests.
- **Prompts are versioned assets kept per language:** `assets/prompts/{en,zh}/system.md`, `chat.md` (free-form chat turns), `generate.md` (composer requests), `modify.md`, `repair.md`, and `translate.md`, with `{{placeholders}}` filled by `PromptBuilder`. Every prompt tells the model to answer in the current app language and to fill in `nameEn`, `grams`, and `aiKcal` for each ingredient. The UI's preset chips ("Full day plan", "3 quick breakfasts", "Make it vegetarian", "Under 500 kcal", and so on) are localized `strings.xml` entries that map to prompt templates.
- **Language:**
  - **Resources:** default resources are English and Chinese lives in `values-zh`. The app uses Android's per-app language support (`LocaleManager` plus `res/xml/locales_config.xml`) and offers "Follow system / English / 中文" in Settings. With "Follow system", a Chinese system locale gives Chinese and anything else gives English.
  - **Prompts:** the prompt language follows the resolved app locale at the moment of generation, and that becomes `Recipe.language`.
  - **Translation:** `RecipeTranslator` sends a recipe through `translate.md` and keeps everything except the text fields and `language`, including `id`, `setId`, numbers, `grams`, and `nameEn`.
  - **Language switch:** `SettingsRepository` stores the last resolved language. When it differs at startup or resume and saved recipes exist in the other language, the app offers to translate them.
- **Generation request:** a composer panel with a meal-scope picker (Full-day set / Breakfast / Lunch / Dinner / Snack / Dessert), a count stepper from 1 to 10 (meaning sets, or recipes per chosen meal), and optional preferences as free text. Free-form chat is also always available.
- **Modify flow:** each recipe card, in the chat or in the saved library, has a **"Modify with AI"** action. It sends `modify.md` with that recipe's JSON and the user's instruction, and the model returns exactly one recipe. A diff-style preview then offers **Replace** or **Save as copy**. Changing one recipe in a set leaves the rest of the set untouched.
- **Export/import:** JSON export (`RecipeExport(version, recipes)`) through the Storage Access Framework, which preserves every recipe field, including `nameEn` and `grams`, and can be re-imported, with dedupe by id. Nutrition tables and keys are never exported. Single recipes can also be shared as Markdown/plain text through the share sheet.
- **Adaptive layout:** the layout is driven by `WindowSizeClass` and Jetpack WindowManager.
  - Compact: single-pane navigation.
  - Medium (unfolded portrait, small tablet): navigation rail, single pane.
  - Expanded (tablet, landscape, desktop window): navigation rail plus library and detail side by side.
  - **Cover mode** (compact width *and* compact height, the near-square small window): a dedicated layout with the essentials only, large touch targets, and a **Cook mode** that shows one step at a time in large type with the screen kept on.
  - The activity is resizable and handles size changes without losing state.

---

## Dependency Graph

```
Gradle scaffold + theme + nav shell + Kover setup (T1)
 ├── i18n resources + language resolution (T2)
 │    └── PromptBuilder + prompt assets (T6)
 ├── Settings: provider config + encrypted keys (T3)
 │    └── LlmClient: OpenAI-compatible streaming, Kover gate on (T4)
 │         ├── LlmClient: Anthropic streaming (T5)
 │         └── Recipe schema + parser + recipe card in chat (T6)
 │              ├── USDA asset + matcher + calculator (T7)
 │              │    └── AppDatabase + CSV table import (T8)
 │              │         └── Room recipes + save + library (T10)
 │              │              ├── Recipe detail screen (T11)
 │              │              │    ├── Translate on language switch (T12)
 │              │              │    ├── Modify saved recipe (T14, after T12 and T13)
 │              │              │    └── Adaptive / Android 17 / full-screen (T17)
 │              │              │         └── Cover layout + Cook mode (T18)
 │              │              ├── Preset chips + chat persistence (T15)
 │              │              └── Export / import / share (T16)
 │              ├── Generation composer: sets/meals/count (T9)
 │              └── Modify recipe in chat (T13, after T9)
Errors + accessibility (T19), then release build + About (T20)
```

**Tasks run one at a time in `tasks/todo.md` order.** The graph shows what each task needs, not what may run concurrently. The only parallel pair is T8 with T9; both add strings to `res/values*/strings.xml`, so expect a merge conflict there and resolve it by keeping both sets. Sibling tasks often edit the same files: T9 and T13 both change `PromptBuilder.kt` and `ChatViewModel.kt`, T12 and T14 both change `RecipeDetailScreen.kt`, and T14 reuses the `ModifySheet` that T13 creates.

T8 creates `AppDatabase` and T10 adds `recipes` to it, so T8 → T10 is sequential: running them in parallel would make two worktrees create the same database and schema version. T9 (composer) touches neither and can run in parallel with T8 (see `AGENTS.md`). T15 adds chat tables to `AppDatabase`, so it runs after T10, not alongside it. T17 needs the library and detail screens (T10, T11) for its list-detail pane, and T18 builds on T17 and needs the detail screen and recipe steps, so both run after Phase 2. T1 already makes every screen edge-to-edge and leaves orientation unlocked.

---

## Task List (vertical slices; checklist in tasks/todo.md)

### Phase 1: Foundation and the riskiest path (a working LLM round-trip)

**Task 1: Project scaffold that builds and launches** *(M)*
- `~/.gradle/gradle.properties` already points Gradle at the Temurin 21 JDK in `~/.jdks`; run every `./gradlew` command with `JAVA_HOME` exported to it. Add the Gradle wrapper (there is no system `gradle`, so fetch the wrapper files or a Gradle distribution directly), `libs.versions.toml`, the `:app` module with `applicationId = "com.elio.recipecraft"`, a Hilt `Application`, `MainActivity` with `enableEdgeToEdge()`, an M3 theme with dynamic color, and a bottom navigation with empty Chat, Library, and Settings screens. Set SDK levels to 37/37/34 and `local.properties` to `sdk.dir`. Configure Kover over `domain` and `data` with the 80% line-coverage rule written but disabled: there is no code to measure yet, and an empty set would either pass vacuously or fail. T4 turns it on. Add a `.gitignore` that covers `local.properties`, build outputs, and keystores.
- AC: `./gradlew assembleDebug lint koverVerify` succeeds, and `./gradlew koverHtmlReport` produces a report. The app launches on `Pixel_10_Pro_API37` (API 37) and switches between the three tabs. Content draws behind the system bars without overlapping them.
- Verify: `./gradlew assembleDebug lint koverVerify`. Launch the emulator, then install and open the app.
- Files: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, `MainActivity.kt`, `ui/theme/*`, `ui/AppNav.kt`, `.gitignore`.

**Task 2: English/Chinese UI with system-language priority** *(S)*
- Add `values/strings.xml` (English) and `values-zh/strings.xml`, plus `locales_config.xml` with `android:localeConfig`. Add `AppLanguage` (FOLLOW_SYSTEM/EN/ZH) and a resolver, and a language picker in Settings that calls `LocaleManager`.
- AC: with the system set to zh-CN the app shows Chinese, with fr-FR it shows English, and an in-app override applies straight away and survives a restart.
- Verify: a unit test for the resolver (zh-CN, zh-TW, zh-HK → ZH; fr, ja → EN). Switch languages manually on the emulator.
- Files: `res/values*/strings.xml`, `res/xml/locales_config.xml`, `domain/AppLanguage.kt`, `ui/settings/LanguagePicker.kt`.

**Task 3: Configure a provider and store the API key encrypted** *(M)*
- Add `ProviderType` (CLAUDE, OPENAI, DEEPSEEK, CUSTOM), `ProviderConfig(type, baseUrl, protocol, model)`, `SecretStore` (Tink AEAD with a Keystore key), and `SettingsRepository` (DataStore). The Settings UI lets the user pick the active provider, mask/unmask the key, edit the model, and, for Custom, enter a base URL and choose a protocol. The OpenAI model starts empty. Exclude secrets from backup rules.
- AC: a saved key survives a restart. The key's plaintext never appears in DataStore files or logcat. Switching providers keeps each provider's settings separately.
- Verify: an instrumented test for the SecretStore round-trip. `adb shell run-as … cat` on the DataStore file shows only ciphertext.
- Files: `data/settings/{SecretStore,SettingsRepository,ProviderConfig,ProviderDefaults}.kt`, `ui/settings/ProviderSettings.kt`, `res/xml/data_extraction_rules.xml`.

**Task 4: Stream a free chat reply through an OpenAI-compatible provider** *(M)*
- Add the `LlmClient` interface and `OpenAiCompatibleClient` (OkHttp SSE), with error mapping (401 → InvalidKey, 429 → RateLimited, network failure, timeout). Add a minimal `ChatViewModel` and `ChatScreen` with a message list, input field, Send/Stop, and streaming text. Add a "Test connection" button and "Fetch models" in Settings. Sending with no model selected prompts the user to pick one. Turn on the Kover 80% rule from T1, adding JVM tests for any `domain`/`data` code from T2 and T3 that the gate finds uncovered (the Keystore-backed part of `SecretStore` stays covered by its instrumented test).
- AC: with a real OpenAI or DeepSeek key, a typed message streams back token by token. Stop cancels the request. A bad key shows a localized "invalid API key" message. `koverVerify` enforces 80% and passes.
- Verify: MockWebServer unit tests with recorded SSE fixtures (normal reply, `[DONE]`, an error body, a mid-stream disconnect). `./gradlew test koverVerify`. A manual run with a real key.
- Files: `data/llm/{LlmClient,LlmEvent,OpenAiCompatibleClient,LlmClientFactory}.kt`, `ui/chat/{ChatScreen,ChatViewModel}.kt`, `test/.../OpenAiCompatibleClientTest.kt`, `app/build.gradle.kts`.

**Task 5: Stream through Claude and Anthropic-compatible custom endpoints** *(S)*
- Add `AnthropicClient` (Messages API SSE, the system prompt as a top-level `system` field, `max_tokens`). Wire it into `LlmClientFactory` for both CLAUDE and CUSTOM with the Anthropic protocol.
- AC: the same chat screen streams from Claude. A custom endpoint works with either protocol.
- Verify: MockWebServer tests with Anthropic SSE fixtures (`message_start`, `content_block_delta`, `message_stop`, `error` event). A manual run with a real key.
- Files: `data/llm/AnthropicClient.kt`, `LlmClientFactory.kt`, `test/.../AnthropicClientTest.kt`.

**Task 6: Generate one recipe and show it as a structured card** *(M)*
- Add the domain models (`Recipe`, `Ingredient` with `nameEn`/`grams`/`aiKcal`, `Calories`) and the `RecipeBatch` schema, and `assets/prompts/{en,zh}/{system,generate,repair}.md`. Add `PromptBuilder`, which picks the prompt language from the resolved locale and stamps it on `Recipe.language`, and `RecipeParser`, which extracts the fenced JSON, decodes it leniently, and runs one automatic repair turn. Add a `RecipeCard` composable with name, meal type, calories, ingredients, tools, and steps, collapsible. Calories show as "≈ AI estimate" until T7.
- AC: "Give me a breakfast recipe" produces a card with all four sections filled in. Every ingredient has a `nameEn`, and `grams` wherever a weight makes sense. In Chinese the recipe text is Chinese while `nameEn` stays English. Malformed JSON triggers one repair attempt and then a readable fallback.
- Verify: unit tests for `RecipeParser` (valid, extra prose, trailing commas, missing optional fields, invalid → repair) and for `PromptBuilder` (en/zh template selection and placeholder filling). A manual run.
- Files: `domain/Recipe.kt`, `data/llm/{PromptBuilder,RecipeParser}.kt`, `assets/prompts/**`, `ui/recipe/RecipeCard.kt`.

**Task 7: Database calorie matching against the bundled USDA table** *(M)*
- `tools/nutrition/build_usda.py` downloads FDC Foundation + SR Legacy, joins them with the hand-curated `aliases.csv`, and writes `app/src/main/assets/nutrition/usda.db` (name, aliases, kcal per 100 g) plus `NOTICE`. The script runs on the developer machine only; the app makes no network call for nutrition data.
- Add `UsdaDatabase` (read-only, `createFromAsset`), `IngredientMatcher` (normalized exact match on name and aliases), and `CalorieCalculator`. The calculator takes an ordered list of table lookups, so T8 can put imported tables in front of USDA without changing it. Add a `CaloriesLabel` that shows the source, and the calorie disclaimer on the card.
- AC: "oatmeal with banana and milk" matches every ingredient through USDA, and its total is within ±10% of a hand calculation. A recipe with one unmatched ingredient shows "≈ AI estimate". The asset is under 1 MB.
- Verify: unit tests for `IngredientMatcher` (normalization, aliases, plurals, no partial matches) and `CalorieCalculator` (all matched → DATABASE; one miss → AI_ESTIMATE; missing grams; lookup order). Run the build script and check the asset size. A manual run with a real key.
- Files: `tools/nutrition/{build_usda.py,aliases.csv}`, `assets/nutrition/{usda.db,NOTICE}`, `data/nutrition/{UsdaDatabase,IngredientMatcher,CalorieCalculator}.kt`, `ui/recipe/CaloriesLabel.kt`.

### Checkpoint A (after T1–T7)
- [ ] `./gradlew test assembleDebug lint koverVerify` all pass
- [ ] Chat → recipe card works with Claude, OpenAI, and DeepSeek, in both English and Chinese
- [ ] Calorie source shows correctly for a USDA-matched recipe and an AI-estimate recipe
- [ ] Human review before continuing (check prompt quality, card layout, `nameEn` quality)

### Phase 2: Core features

**Task 8: Import your own nutrition tables** *(M)*
- Create `AppDatabase` (schema version 1, exported to `app/schemas/`) with the `nutrition_tables(id, name, priority, importedAt)` and `nutrition_foods(tableId, name, aliases, kcalPer100g)` entities and `NutritionTableDao`. T10 adds the `recipes` table to this same database. Add `CsvTableImporter` (UTF-8, header `name,aliases,kcal_per_100g`; invalid rows are skipped and counted). Feed imported tables to `CalorieCalculator` in priority order ahead of USDA, matching `zh` recipes on `name` and `en` recipes on `nameEn`.
- Add Settings > Nutrition tables: import CSV through SAF `OpenDocument`, list, reorder priority, delete.
- AC: importing a small Chinese CSV that contains 番茄 and 鸡蛋 makes 番茄炒蛋 show a database total, and deleting that table turns it back into "≈ AI estimate" when only imported tables are consulted (in the app, USDA's `nameEn` match may still give a database total). A CSV with bad rows imports the good rows and reports how many were skipped.
- Verify: unit tests for `CsvTableImporter` (good file, bad rows, BOM, wrong header) and for the zh/en lookup key choice. A Room test for the table priority order. A manual import through the SAF picker.
- Files: `data/AppDatabase.kt`, `data/nutrition/{NutritionEntities,NutritionTableDao,CsvTableImporter}.kt`, `CalorieCalculator.kt`, `ui/settings/NutritionTables.kt`, `res/values*/strings.xml`.

**Task 9: Generation composer for full-day sets, single meals, and a count** *(M)*
- Add the composer UI (scope picker, count stepper from 1 to 10, preferences field) and `generate.md` parameters for scope and count. Batches render as grouped cards, with sets shown under a "Day 1 / Day 2" header.
- AC: "Full-day set × 2" yields 2 × {breakfast, lunch, dinner} grouped by set. "Dinner × 3" yields three distinct dinners. The count is respected, and the app warns when the model returns fewer.
- Verify: unit tests for the request → prompt mapping and for set grouping. A manual run of each scope.
- Files: `ui/chat/{GenerationComposer,ChatScreen,ChatViewModel}.kt`, `domain/GenerationRequest.kt`, `PromptBuilder.kt`, `assets/prompts/*/generate.md`, `res/values*/strings.xml`.

**Task 10: Save recipes and browse the library** *(M)*
- Add `RecipeEntity` with converters, `RecipeDao`, and `RecipeRepository`, and add the `recipes` table to the `AppDatabase` that T8 created (no release has shipped, so bump the version without a migration). Each card gets a Save action, and each set gets "Save all". The Library screen lists recipes with a meal-type filter, search by name or ingredient, and an empty state.
- AC: saved recipes survive app restarts. Filtering and search work. Saving the same recipe twice doesn't create a duplicate.
- Verify: in-memory Room DAO tests and a Compose UI test for save → library.
- Files: `data/AppDatabase.kt`, `data/recipe/{RecipeEntity,RecipeDao,RecipeRepository,Converters}.kt`, `ui/library/LibraryScreen.kt`.

**Task 11: Recipe detail screen and delete with undo** *(S)*
- Add a detail screen with sections for ingredients (calories per item when matched), tools, numbered steps (with minutes), calories in total and per serving with their source, servings, and the calorie disclaimer. Delete with undo.
- AC: every field in the schema is visible, the screen reads well in both languages, and delete can be undone from the snackbar.
- Verify: a Compose UI test for the detail and delete/undo flow.
- Files: `ui/recipe/{RecipeDetailScreen,RecipeDetailViewModel}.kt`.

**Task 12: Translate recipes when the language changes** *(M)*
- Add `assets/prompts/{en,zh}/translate.md` and `RecipeTranslator`, which sends a recipe's JSON and gets back the same recipe with only its text fields and `language` changed. Validate that `id`, `setId`, counts, numbers, `grams`, and `nameEn` are unchanged, and reject the result otherwise. Store the last resolved language in `SettingsRepository`. When the language changes and saved recipes exist in the other language, show a dialog: "N saved recipes are in English. Translate them to 中文 with AI?" with **Translate** and **Not now**. Translation runs as a cancellable batch with progress, and recipes that fail stay as they were. Add a **Translate** action on a detail screen whose recipe is in the other language. Recalculate calories after translation, since `zh` matching uses `name`.
- AC: switching to Chinese with three English recipes saved offers the dialog. Translate converts all three, and their ids, sets, grams and calories are unchanged. Not now leaves them in English, and the detail action still works. Cancelling mid-batch keeps the recipes already translated and leaves the rest untouched.
- Verify: unit tests for the translation validator (accepts text-only changes; rejects a changed id, a changed number, or a missing step) and for the language-change detection. A ViewModel test for the batch with cancellation. A manual run with a real key.
- Files: `assets/prompts/*/translate.md`, `data/llm/RecipeTranslator.kt`, `data/settings/SettingsRepository.kt`, `ui/library/TranslateDialog.kt`, `ui/recipe/RecipeDetailScreen.kt`.

**Task 13: Change one recipe in the chat** *(M)*
- Add `modify.md` (en/zh). "Modify with AI" on a chat card opens an instruction field with quick chips ("vegetarian", "fewer calories", "no oven", "swap an ingredient"). The reply replaces that card only, and the other recipes in the set stay the same. Recalculate calories on the result.
- AC: changing the lunch in a set updates only the lunch. The changed card shows a "modified" marker and can be undone.
- Verify: a unit test that the modify prompt carries the target recipe's JSON and that the result keeps the same id and setId. A manual run.
- Files: `assets/prompts/*/modify.md`, `ui/chat/ModifySheet.kt`, `ChatViewModel.kt`, `PromptBuilder.kt`.

**Task 14: Change a saved recipe from its detail screen** *(S)*
- Reuse `ModifySheet` on the detail screen, then show a preview of the old and new versions with **Replace** or **Save as copy**.
- AC: Replace updates the saved record and `updatedAt`. Save as copy adds a new record. Cancelling leaves the original as it was.
- Verify: a repository test for replace versus copy, and a manual run.
- Files: `ui/recipe/{RecipeDetailScreen,ModifyPreview}.kt`, `RecipeRepository.kt`.

**Task 15: Preset prompt chips and chat history that persists** *(M)*
- Add localized preset chips on an empty chat and above the input. Add Room `conversations` and `messages` tables, a "New chat" action, and a recent-chats list. Chat state survives process death and fold/unfold.
- AC: chips show in the current language and send the matching template. Killing the app restores the last conversation.
- Verify: a DAO test, and a manual run of `adb shell am kill` followed by relaunching the app.
- Files: `ui/chat/PresetChips.kt`, `data/chat/{ConversationEntity,MessageEntity,ChatDao,ChatRepository}.kt`, `res/values*/strings.xml`.

### Checkpoint B (after T8–T15)
- [ ] All tests pass and the build is clean, with Kover ≥ 80%
- [ ] An imported CSV gives 番茄炒蛋 a database total through that table, and a zh recipe with an ingredient no table matches shows "≈ AI estimate"
- [ ] End to end: generate a set → modify one meal → save → open detail → modify again → replace → switch language → translate
- [ ] Human review

### Phase 3: Devices and polish

**Task 16: Export, import, and share** *(S)*
- Add JSON export of all or selected recipes through SAF `CreateDocument`, import through `OpenDocument` with version check and dedupe, and Markdown share for a single recipe. Imported recipes have their calories recalculated against the local tables.
- AC: exporting, deleting everything, and importing restores identical recipes. Files from a newer schema version are rejected with a clear message. Keys and nutrition tables are never included.
- Verify: a unit test for the export → import round-trip, and a manual run through the SAF picker.
- Files: `data/recipe/{RecipeExport,ExportImportService}.kt`, `ui/library/ExportImportActions.kt`, `ui/recipe/ShareRecipe.kt`.

**Task 17: Adaptive and full-screen layouts and Android 17 compliance** *(M)*
- Add `WindowSizeClass`-driven navigation (bottom bar, rail, or list-detail) and handle display cutouts and insets on every screen. Support predictive back. Review the official Android 17 behavior changes for apps targeting API 37 and fix anything that applies. Declare `resizeableActivity`. Make sure no screen locks orientation.
- AC: there's no clipping or overlap on the API 37 emulator in portrait, landscape, a resizable desktop-style window, or a tablet size. Predictive back animates. The app has no lint warnings about targeting.
- Verify: Compose previews at compact, medium, and expanded sizes. Manual runs on the resizable emulator and on the `Pixel_10_Pro_API37` AVD in both orientations.
- Files: `ui/AppNav.kt`, `ui/AdaptiveScaffold.kt`, `ui/library/LibraryDetailPane.kt`, `AndroidManifest.xml`.

**Task 18: Samsung Z Flip 8 cover-screen layout and Cook mode** *(M)*
- Detect cover mode (compact width and compact height, about a square window) and give the chat, library, and detail screens a condensed layout with targets of at least 48dp. Add **Cook mode**: one step per page, swipe or buttons to move, large type, `keepScreenOn`, and a timer when the step has minutes. Keep state when moving between the cover and main screens.
- AC: on the **real Z Flip 8** cover screen, every screen is usable without horizontal scrolling. Cook mode works one-handed. Unfolding keeps the current screen and step.
- Verify: Compose previews at the cover window size and a custom AVD at the cover resolution during development. Final acceptance on the real Z Flip 8 (install with `adb` over USB or wireless debugging).
- Files: `ui/cover/{CoverLayout,CookModeScreen}.kt`, `ui/AdaptiveScaffold.kt`, `ui/recipe/RecipeDetailScreen.kt`.

**Task 19: Error messages and accessibility** *(M)*
- Localize every error state (no key set, no model selected, invalid key, rate limit, offline, timeout, parse failure, model returned fewer recipes, translation rejected, CSV import errors) in one `ErrorMessages` mapping. Retry with backoff when rate-limited. Add TalkBack labels and check every screen at 200% font scale.
- AC: every error has a localized, actionable message in both languages. Rate-limited requests retry with backoff before showing the error. Accessibility Scanner reports nothing critical.
- Verify: a unit test that every `LlmErrorKind` and import error maps to a string in both `values/` and `values-zh/`, a unit test for the backoff schedule, and `./gradlew connectedDebugAndroidTest`. A manual Accessibility Scanner pass.
- Files: `ui/common/ErrorMessages.kt`, `data/llm/RetryPolicy.kt`, `res/values*/strings.xml`, plus content descriptions across screens.

**Task 20: Release build and About screen** *(S)*
- Configure R8 rules for kotlinx.serialization, Room, and Tink. Add a signed release configuration that reads the keystore from properties outside git (the user supplies the keystore). Add Settings > About with the version, the calorie disclaimer, and the USDA attribution from `NOTICE`.
- AC: the release APK runs generation, calorie matching, CSV import, and export without crashing. No keystore or signing secret is in the repo. About shows the disclaimer and attribution in both languages.
- Verify: `./gradlew test koverVerify assembleRelease`, then a manual run of the release APK on the Z Flip 8.
- Files: `app/proguard-rules.pro`, `app/build.gradle.kts`, `ui/settings/AboutSection.kt` (a Settings section, like Nutrition tables), `README.md`, `keystore.properties.example`.

### Checkpoint C (complete)
- [ ] All `SPEC.md` success criteria met, and unit and instrumented tests pass
- [ ] The release build works on API 37 and on the real Z Flip 8 (cover and main screens)
- [ ] Human review before any distribution

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| No JDK on PATH | High (blocks T1) | Resolved before T1: the user chose a user-local Temurin 21 in `~/.jdks`, wired through `~/.gradle/gradle.properties`, with `JAVA_HOME` exported before each `./gradlew` run |
| LLMs return malformed or partial JSON | High | Lenient decoding, one automatic repair turn, a raw-text fallback, and parser tests built from real captured replies |
| `nameEn` doesn't line up with USDA names, so few recipes get a database total | Med | Prompt the model for simple common names with examples, curate `aliases.csv` from real captured replies, and log unmatched names in debug builds to grow the alias list. The AI estimate stays an honest fallback |
| Model's `grams` are wrong for cups, counts and so on | Med | The spec accepts this. Calories are labelled approximate and carry a disclaimer |
| Translation changes numbers or drops steps | Med | A validator rejects any translation that changes anything other than text fields; the recipe stays as it was |
| No openly licensed Chinese nutrition table | Low | Chinese ingredients use an imported CSV or the AI estimate (decided in `SPEC.md`) |
| Provider SSE formats differ or change | Med | Two separate clients with fixture-based tests, and a custom endpoint that lets the user pick the protocol |
| Samsung's policy for apps on the cover screen | Med | Design for the size class instead of exact pixels and verify on the real Z Flip 8. Users may have to enable cover-screen apps through Samsung's settings or Good Lock, which the app can't control |
| Android 17 behavior changes not yet reviewed | Med | A dedicated review step in T17 against the official list for targetSdk 37 |
| Model IDs go stale | Low | Editable model field, a "Fetch models" button, and defaults in one file |
| API keys leak through backup or logs | High | Tink plus the Keystore, backup exclusion rules, no request/response body logging in release builds |

## Open Questions

None. All were resolved in `SPEC.md` on 2026-10-27.

---

## Verification (whole project)

- Unit tests and coverage: `./gradlew test koverVerify` (parsers, prompt builder, SSE clients against MockWebServer, language resolver, matcher, calorie calculator, CSV importer, translation validator, export round-trip).
- Instrumented tests: `./gradlew connectedDebugAndroidTest` on the API 37 `Pixel_10_Pro_API37` AVD (Room, SecretStore, Compose flows).
- Manual runs with real keys for Claude, OpenAI, and DeepSeek in both English and Chinese, on the phone AVD, a resizable or tablet AVD, and the real Z Flip 8 (cover and main screens).
