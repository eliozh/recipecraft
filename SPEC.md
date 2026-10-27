# Spec: RecipeCraft

Status: **approved** · 2026-10-27
How the work gets done is in `tasks/plan.md` and `tasks/todo.md`. Agent workflow and commit rules are in `AGENTS.md`.

## Objective

RecipeCraft is an Android app for one person cooking at home. They chat with an LLM of their choice, which generates recipes. They refine single recipes and keep the ones they like. It is a **free, personal, non-commercial** app with no ads, payments or accounts. The user brings their own API key, and nothing leaves the device except calls to the LLM provider they configure.

### User stories

1. As a user, I can set up Claude, OpenAI, DeepSeek or a custom endpoint with my own API key. The app stores the key encrypted.
2. I can ask for a **full-day set** (breakfast, lunch and dinner) or for **single meals** (breakfast, lunch, dinner, snack or dessert), and choose **how many** (1–10).
3. Each recipe shows **ingredients** (with quantities), **kitchen tools**, **numbered steps**, **servings** and **calories** (total and per serving).
4. I can ask the AI to **change one recipe**, in the chat or from my library, and the rest of a set stays the same.
5. I can **save** recipes, browse, filter and search them, delete them with undo, and **export/import** them as JSON or share one as text.
6. The UI and the AI's replies are in **English or Chinese**. The app follows the system language and falls back to English, and I can override this in Settings.
7. A recipe keeps the language it was **generated in** (the resolved app language at that moment), for its name, ingredients, tools and steps. When I switch the app language, I'm asked whether to translate my saved recipes with the LLM.
8. The app works on phones, foldables, tablets and resizable windows, and on the **Samsung Z Flip 8 cover screen**, where a **Cook mode** shows one step at a time.

### Recipe language and translation

- `Recipe.language` (`en` or `zh`) is set at generation and never changes on its own. Switching the app language translates the UI only.
- **On a language switch:** if any saved recipes are in the other language, a dialog says "N saved recipes are in English. Translate them to 中文 with AI?" with **Translate** and **Not now**. A recipe detail screen in the other language also has a **Translate** action.
- **Translation** sends each recipe's JSON through `translate.md` (en/zh) and updates it **in place**. It keeps the `id`, `setId`, numbers, grams and the hidden match keys, and changes only the text fields and `language`. It runs as a cancellable batch with progress, and a recipe whose translation fails is left as it was.
- Chats are not translated.

### Calories

Every calorie figure shows its source:

- **Database (preferred):** for each ingredient, the LLM returns the displayed name (in the recipe's language), a hidden English match key `nameEn`, and `grams` (the app trusts the model's conversion from cups, counts and so on). The app matches each ingredient against the **enabled nutrition tables** and computes kcal. A recipe shows the database total only when every ingredient that has calories matched.
- **AI estimate (fallback):** if any ingredient fails to match, the app shows the model's estimate instead, labelled "≈ AI estimate".
- A disclaimer ("Calories are approximate and not medical advice") appears on the recipe detail screen and in Settings > About.

**Nutrition tables:**

- **Built in:** USDA FoodData Central (Foundation + SR Legacy), public domain, bundled and matched on `nameEn`.
- **Chinese ingredients:** the only Chinese source is the mainland **China Food Composition Table** (中国食物成分表, China CDC). It is copyrighted and not openly released, so the app **does not ship, download or link to it**. Taiwan and other non-mainland tables are out of scope.
- **Import your own** (Settings > Nutrition tables): the user imports a CSV of a table they are entitled to use, such as their own transcription of the China table. The app stores it on the device and never exports it. In `zh` recipes it is matched on the ingredient's Chinese display name, and in `en` recipes on `nameEn`. The CSV format is UTF-8 with a header row:
  ```csv
  name,aliases,kcal_per_100g
  鸡蛋,鸡子|蛋,144
  ```
  `name` and `kcal_per_100g` are required, and `aliases` is an optional `|`-separated list. Rows that fail validation are skipped and counted in the import summary.
- **Matching order:** imported tables first, then USDA. Any ingredient that matches no table makes the recipe fall back to "≈ AI estimate", which is the normal result for Chinese recipes when no table has been imported.

## Tech Stack

| Area | Choice |
|---|---|
| Language / build | Kotlin, Gradle Kotlin DSL, version catalog `gradle/libs.versions.toml`, JDK 21 |
| SDK | `compileSdk 37`, `targetSdk 37`, `minSdk 34` |
| UI | Jetpack Compose, Material 3 (dynamic color), Navigation Compose, material3-adaptive, WindowManager |
| DI | Hilt |
| Storage | Room (recipes, chats, nutrition DB), DataStore (preferences), Tink AEAD with an Android Keystore key (API keys) |
| Network | OkHttp with okhttp-sse, kotlinx.serialization |
| Tests | JUnit 4, kotlinx-coroutines-test, Turbine, MockWebServer, Room in-memory, Compose UI test, Kover |
| App ID | `com.elio.recipecraft` |

Library versions are the latest stable releases at Task 1, pinned in the version catalog. Upgrading a library afterwards is an "Ask first" change.

## Commands

Run from the repo root once Task 1 is done, with `JAVA_HOME` exported as described in `AGENTS.md` (Build and verify):

```bash
./gradlew assembleDebug                        # build the debug APK
./gradlew installDebug                         # install on the running emulator/device
./gradlew test                                 # JVM unit tests
./gradlew connectedDebugAndroidTest            # instrumented tests (AVD or device attached)
./gradlew lint                                 # Android lint
./gradlew koverVerify                          # coverage gate
./gradlew assembleRelease                      # signed release (needs the keystore properties)
./gradlew test assembleDebug lint koverVerify  # the pre-commit gate
```

## Project Structure

```
app/src/main/java/com/elio/recipecraft/
  domain/          → pure Kotlin models and logic (Recipe, MealType, GenerationRequest, AppLanguage); no Android imports
  data/llm/        → LlmClient, AnthropicClient, OpenAiCompatibleClient, PromptBuilder, RecipeParser
  data/AppDatabase.kt → the Room database shared by recipes, chats and imported nutrition tables
  data/recipe/     → RecipeEntity/Dao/Repository, export/import
  data/chat/       → conversations and messages (Room)
  data/nutrition/  → table store, CSV import, matcher, calorie calculator
  data/settings/   → SettingsRepository (DataStore), SecretStore (Tink), ProviderDefaults
  ui/              → AppNav, AdaptiveScaffold, theme/, common/
  ui/{chat,library,recipe,settings,cover}/ → screens with their ViewModels
app/src/main/assets/prompts/{en,zh}/ → system.md, generate.md, modify.md, repair.md, translate.md
app/src/main/assets/nutrition/       → bundled USDA DB, NOTICE (sources and licenses)
app/src/main/res/values{,-zh}/       → strings (English default, Chinese)
app/src/test/                        → JVM unit tests, mirroring the main package
app/src/test/resources/fixtures/     → recorded SSE streams and LLM replies (no real keys)
app/src/androidTest/                 → Room, SecretStore and Compose UI tests
app/schemas/                         → exported Room schemas for migration tests
tools/nutrition/                     → script that builds the nutrition DB asset from the source data
tasks/                               → plan.md, todo.md
```

## Code Style

The official Kotlin style, the Android lint defaults and Compose API guidelines:

```kotlin
// One public composable per screen, state hoisted into the ViewModel.
@Composable
fun RecipeCard(
    recipe: Recipe,
    onModify: (Recipe) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Text(recipe.name, style = MaterialTheme.typography.titleMedium)
        CaloriesLabel(recipe.calories) // shows "≈ AI estimate" when source == AI
    }
}

// Errors are values, not exceptions, across layer boundaries.
sealed interface LlmEvent {
    data class Delta(val text: String) : LlmEvent
    data class Done(val usage: Usage?) : LlmEvent
    data class Error(val kind: LlmErrorKind) : LlmEvent
}
```

- Names: `PascalCase` types, `camelCase` members, screens `XxxScreen`, ViewModels `XxxViewModel`, a single `XxxUiState` data class per screen.
- Every user-facing string goes in `strings.xml`, in both `values/` and `values-zh/`.
- Coroutines use structured concurrency (`viewModelScope`) and inject their dispatchers. Never use `GlobalScope`.
- Comments explain *why*, in one line where possible. Commit rules are in `AGENTS.md`.

## Testing Strategy

| Level | Covers | Where |
|---|---|---|
| Unit (JVM) | `RecipeParser`, `PromptBuilder`, both LLM clients against MockWebServer SSE fixtures, language resolver, ingredient matcher and calorie calculator, CSV table importer (using small fixture files), translation keeping ids and numbers, export/import round-trip, ViewModels via Turbine | `app/src/test/` |
| Instrumented | Room DAOs and migrations, SecretStore round-trip, Compose flows (save → library, detail, delete/undo, modify → replace/copy) | `app/src/androidTest/` |
| Manual | real keys for 3 providers × 2 languages; phone, tablet and resizable AVDs; **real Z Flip 8** cover and main screens | checklist at each Checkpoint |

- Coverage: Kover gate **≥ 80% line coverage on `domain` and `data`**. UI code is excluded from the gate and covered by the Compose tests above.
- A bug fix starts with a failing test that reproduces it.
- LLM fixtures are captured from real replies, then scrubbed of keys and IDs.

## Boundaries

**Always**
- Run `./gradlew test assembleDebug lint koverVerify` and pass the review gate in `AGENTS.md` before every commit.
- Add new strings and prompts in both English and Chinese.
- Keep API keys in `SecretStore` only.
- Decode LLM output leniently, and handle every `LlmEvent.Error`.
- Update `SPEC.md` before implementing a change that contradicts it.

**Ask first**
- Adding or upgrading a dependency.
- Changing the Room schema of a released version, or the export format version.
- Changing the prompt JSON schema (`RecipeBatch`).
- Installing system packages, such as the JDK.
- Making network calls to anything other than the LLM provider the user configured.
- Adding a nutrition data source (its license must be checked and recorded first).
- Changing `minSdk`/`targetSdk`, or adding permissions to the manifest.

**Never**
- Commit, log or export API keys or the release keystore.
- Log request or response bodies in release builds.
- Add analytics, telemetry or crash reporting that phones home.
- Skip, delete or `@Ignore` a failing test, or lower the Kover threshold, without approval.
- Lock orientation or disable resizability.
- Bundle, mirror or link to a nutrition table whose license does not allow it.

## Success Criteria

1. With a real key for each of Claude, OpenAI and DeepSeek, "Full-day set × 2" produces 2 × {breakfast, lunch, dinner}, streamed and rendered as grouped cards, in both English and Chinese.
2. Every recipe card shows ingredients, tools, numbered steps, servings and calories, with a visible calorie source (database or AI estimate).
3. For a recipe made of common whole foods (such as "oatmeal with banana and milk"), every ingredient matches the database and the total is within ±10% of a hand calculation from the same data. With a CSV table imported that contains its ingredients, a Chinese recipe (such as 番茄炒蛋) matches every ingredient. Without one, it shows "≈ AI estimate".
4. Every recipe keeps its generation language. Switching the language offers to translate. A translated recipe keeps its id, set, grams and calories, and only its text changes.
5. Modifying one recipe in a set changes only that recipe. Modifying a saved recipe offers Replace or Save as copy, and both behave as described.
6. Saved recipes survive restarts. Export → delete all → import restores identical records.
7. The API key never appears in plaintext on disk, in logcat or in exports (verified with `adb run-as`).
8. On a zh-CN system the app is in Chinese, on fr-FR it is in English, and the in-app override applies straight away.
9. There is no clipping or overlap on API 37 at the compact, medium and expanded size classes. On the **real Z Flip 8 cover screen**, every screen works without horizontal scrolling, Cook mode is usable one-handed, and unfolding keeps the current screen and step.
10. The pre-commit gate passes. The release APK generates recipes without crashing.

## Open Questions

None. Resolved on 2026-10-27:
- **Chinese nutrition data:** "Import your own" CSV plus the AI-estimate fallback. If China CDC ever grants a license, bundling its table becomes a new task.
- **Coverage gate:** 80%.
