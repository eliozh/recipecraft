# RecipeCraft: Todo

## Phase 1: Foundation and LLM round-trip
- [x] T1 Project scaffold that builds and launches (JDK, Gradle, Compose shell, SDK 37/37/34, Kover set up with the rule off, .gitignore)
- [x] T2 English/Chinese UI with system-language priority
- [x] T3 Configure a provider and store the API key encrypted
- [x] T4 Stream a free chat reply through an OpenAI-compatible provider (OpenAI/DeepSeek/custom); turn on the Kover 80% gate
- [ ] T5 Stream through Claude and Anthropic-compatible custom endpoints
- [ ] T6 Generate one recipe and show it as a structured card (schema with nameEn/grams, prompts, parser)
- [ ] T7 Database calorie matching against the bundled USDA table (asset, matcher, calculator, calorie label)
- [ ] Checkpoint A: tests, build, lint, and Kover pass; recipe card works with 3 providers × 2 languages; calorie source correct; human review

## Phase 2: Core features
- [ ] T8 Import your own nutrition tables (creates AppDatabase; CSV import, Settings > Nutrition tables)
- [ ] T9 Generation composer for full-day sets, single meals, and a count (can run in parallel with T8)
- [ ] T10 Save recipes and browse the library (filter, search; after T8)
- [ ] T11 Recipe detail screen and delete with undo
- [ ] T12 Translate recipes when the language changes
- [ ] T13 Change one recipe in the chat
- [ ] T14 Change a saved recipe from its detail screen (replace or save as copy)
- [ ] T15 Preset prompt chips and chat history that persists
- [ ] Checkpoint B: full flow end to end including translation and CSV calorie matching; human review

## Phase 3: Devices and polish
- [ ] T16 Export, import, and share
- [ ] T17 Adaptive and full-screen layouts and Android 17 compliance
- [ ] T18 Samsung Z Flip 8 cover-screen layout and Cook mode (real device)
- [ ] T19 Error messages and accessibility
- [ ] T20 Release build and About screen (R8, signing, disclaimer, USDA attribution)
- [ ] Checkpoint C: all SPEC.md success criteria met; release build verified on the Z Flip 8; human review
