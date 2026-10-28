# AGENTS.md

RecipeCraft is an Android app (Kotlin, Compose, Hilt, Room) for generating recipes by chatting with an LLM. The plan, with architecture, acceptance criteria and verify steps for every task, lives in `tasks/plan.md`. Progress is tracked in `tasks/todo.md`.

## Workflow: you orchestrate, subagents work

The main agent is the **orchestrator**. It plans, delegates, reviews and commits. Each task goes to its own subagent.

For each unchecked task in `tasks/todo.md`, in order:

1. **Dispatch a task subagent.** Start a fresh subagent for exactly one task (one `T<n>` line). Its brief contains:
   - the full task section copied from `tasks/plan.md` (description, AC, Verify, Files)
   - the Architecture Decisions it depends on
   - what earlier tasks produced that it builds on (file paths, interfaces)
   - its deliverable: the code changes, the Verify commands run with their results, and a short report listing changed files, any AC it could not meet, and open questions.

   The task subagent edits files and runs builds and tests. It leaves git to the orchestrator, so it never commits.
2. **Check the report.** The task is done when every AC is met and every Verify command passes. If the report shows a gap, send the gap back to the same subagent and keep going until the criteria hold. Bring a decision only the user can make (installing a JDK, choosing a model default, providing a keystore) to the user.
3. **Stage and review.** Stage the change with `git add`, then run the review gate described below.
4. **Commit** using the Conventional Commits rules below, then tick the task in `tasks/todo.md` in the same commit.
5. **At a checkpoint** (A, B, C), stop for human review before dispatching the next phase.

Tasks the Dependency Graph section of `tasks/plan.md` names as a parallel pair (only T8 with T9) may run as parallel subagents in separate git worktrees. Each one still goes through its own review and commit.

## Review gate: every commit

Before **every** commit, including small fixes and docs, start a fresh **review subagent**. Its only input is the staged diff (`git diff --staged`) and the task's AC, and it has no history from the task subagent, so it reads the change cold. It runs a simple review:

- **Correctness:** the diff does what the commit message claims and meets the AC, with no obvious bugs, crashes or unhandled error paths.
- **Secrets:** no API keys, tokens or keystores, and no logging of keys or request/response bodies.
- **Scope:** the diff only touches what the task needs. No leftover debug code, commented-out blocks or unrelated reformatting.
- **Tests:** new logic comes with the tests the task's Verify line calls for.
- **i18n:** user-facing strings are in `strings.xml` in both `values/` and `values-zh/`, never hardcoded.
- **Readability:** names and structure match the surrounding code.

It returns `PASS`, or `FIX` with a list of `file:line` and the problem for each finding. On `FIX`, have the change corrected, re-stage it, and dispatch a **new** review subagent. Commit only on `PASS`. If the second round still returns `FIX`, take the findings to the user.

In Claude Code: use `general-purpose` for task subagents and `agent-skills:code-reviewer` for the review subagent, with a brief that limits it to the checklist above.

## Commits: Conventional Commits 1.0

```
<type>(<scope>): <subject>

<body>

<footer>
```

- **type**, one of:
  - `feat`: a user-visible feature
  - `fix`: a bug fix
  - `refactor`: a code change that keeps behavior the same
  - `perf`: a performance improvement
  - `test`: tests only
  - `docs`: documentation, including `tasks/*.md` and this file
  - `style`: formatting only
  - `build`: Gradle, dependencies, the wrapper
  - `ci`: CI configuration
  - `chore`: anything else that touches neither app code nor tests
- **scope**: the area touched: `llm`, `recipe`, `chat`, `library`, `settings`, `i18n`, `prompts`, `cover`, `ui`, `db`, `build`. Leave it out when the change spans many areas.
- **subject**: imperative, lowercase, no trailing period, at most 72 characters: `feat(llm): stream replies from anthropic messages api`.
- **body**: optional. Explains *why*, wrapped at 72 columns. Name the task: `Task: T4`.
- **footer**: `BREAKING CHANGE: <what and how to migrate>` for an incompatible change, such as a Room schema change without a migration or a change to the export format, or mark it with `!` after the type or scope (`feat(db)!: ...`).
- Each commit is **atomic**: one logical change that builds and passes tests on its own. A task may take several commits, and each one passes the review gate.

## Build and verify

Run from the repo root once Task 1 has set up the wrapper. This machine has no system JDK, so export `JAVA_HOME` first (Temurin 21, user-local):

```bash
export JAVA_HOME=$HOME/.jdks/jdk-21.0.13+9
./gradlew test assembleDebug lint koverVerify   # the pre-commit gate (koverVerify enforces 80% from T4)
./gradlew connectedDebugAndroidTest   # needs the Pixel_10_Pro_API37 AVD running
```

`.github/workflows/ci.yml` runs the pre-commit gate on every push to `main` and every pull request, and uploads `app/build/reports/` when it fails. Instrumented tests run only locally. If you change the gate, update the workflow too. `.github/dependabot.yml` opens weekly grouped update PRs for Gradle dependencies and GitHub Actions.

## Releases

`.github/workflows/release.yml` runs when a `vX.Y.Z` tag is pushed to GitHub. It runs `test lint koverVerify`, builds a signed `assembleRelease` and publishes a GitHub Release with one APK per ABI (`RecipeCraft-vX.Y.Z-<abi>.apk` for `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, plus `universal`) and `SHA256SUMS.txt` attached. The ABI list is in the `splits` block of `app/build.gradle.kts`. If you change it, update the loop in the workflow too. Nothing runs for a tag that exists only locally.

- **Version:** the tag sets it. `appVersion()` in `app/build.gradle.kts` reads `RECIPECRAFT_VERSION` and computes versionCode = X·10000 + Y·100 + Z, so Y and Z stay below 100. Local builds are 0.1.0. Don't hardcode a version in Gradle.
- **Signing:** the release keystore is `~/.recipecraft/release.jks`, and its values are in `~/.recipecraft/keystore.properties`. CI gets it from the repo secrets `RECIPECRAFT_KEYSTORE_BASE64`, `RECIPECRAFT_KEYSTORE_PASSWORD`, `RECIPECRAFT_KEY_ALIAS` and `RECIPECRAFT_KEY_PASSWORD`. Every release must use this keystore, because an APK signed with another key can't update an installed app. Never print, commit or regenerate it.
- **Cutting a release:** the user decides when to release, and only the user tags and pushes. When `main` has gathered user-visible changes worth shipping (a finished checkpoint, a batch of `feat`/`fix` commits, or a fix for a bug users hit), tell the user it's time to release. Propose the next version from the commits since the last tag (`git describe --tags --abbrev=0`): any `feat` → minor, only `fix`/`perf` → patch, any `BREAKING CHANGE` → major (while on 0.x, minor). Give the commands:

  ```bash
  git tag -a v0.2.0 -m "RecipeCraft v0.2.0" && git push origin v0.2.0
  ```

## Hard rules

- API keys go only into `SecretStore` (Tink plus the Keystore). They never appear in logs, exports, fixtures or commits. Test fixtures use placeholder keys.
- Every user-facing string and every prompt asset exists in both English and Chinese.
