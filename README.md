# Gradle Best Practices with Codex

A self-contained demo: run the official [`gradle-best-practices`](https://github.com/gradle/gradle-skills/skills) skill against a small Gradle build that deliberately violates a cluster of documented best practices, using [Codex](https://developers.openai.com/codex/).

Nothing here bundles a skill. `sample-carlog/` is a plain Gradle project; you install the skill separately, from the official Gradle skills repository.

**Prerequisites:** JDK 17+ on `PATH`, the `codex` CLI, Node for Route 1 (`brew install node`), and network access at run time. If you installed the ChatGPT desktop app, the Codex binary ships inside it and is *not* on `PATH` — symlink it:

```bash
ln -s /Applications/ChatGPT.app/Contents/Resources/codex /opt/homebrew/bin/codex
```

## 1. Install the skill

Pick **one** route. Codex reads both locations at once, so doing both gives you two skills with the same name and no way to say which you meant.

**Route 1 — `npx skills add`, project-local.** Run from the repository root:

```bash
npx skills add gradle/gradle-skills --skill gradle-best-practices -y
```

Installs to `./.agents/skills/gradle-best-practices/`, inside this checkout (gitignored) rather than your home directory. `-g` installs to `~/.agents/skills/` instead; drop `--skill` to install both skills. Codex walks up from its working directory, so the skill is still found when you run the demo from `sample-carlog/`.

**Route 2 — just ask Codex.** No tooling, no flags:

```text
Install gradle-best-practices from https://github.com/gradle/gradle-skills/skills
```

Codex's bundled `skill-installer` skill picks this up, works out the repo layout itself, and installs both skills in one call. It is **machine-wide** — it writes to `~/.codex/skills/`, so the skill appears in every project — it costs model tokens, it refuses to overwrite an existing install, and the skill only goes live on the *next* turn.

Confirm either way, for free:

```bash
codex debug prompt-input "hi" | grep -o 'gradle-best-practices[^"]\{0,80\}'
```

That renders the prompt Codex *would* send, with no model call. Run it from the directory you intend to run the demo from.

## 2. Run it against the sample project

```bash
cd sample-carlog

codex exec \
  --sandbox workspace-write \
  --add-dir ~/.gradle \
  -c sandbox_workspace_write.network_access=true \
  -c shell_environment_policy.inherit=all \
  "This Gradle build is valid, but it probably has issues with things not done
   according to Gradle best practices. Investigate the build configuration and
   apply as many of Gradle's best practices as possible.

   As you make improvements, \`./gradlew build\` must still succeed and the tests
   must still pass. No library source file may be deleted or rewritten — moving or
   renaming one is fine."
```

Run it from inside `sample-carlog/`, not the repository root, so the agent's workspace is the Gradle project alone and it does not read this README's findings list as a hint. Expect roughly 15–25 shell commands and a few minutes. Drop `exec` and the prompt to drive it interactively instead.

| Flag | Why |
|---|---|
| `--sandbox workspace-write` | The skill's job is to *apply* fixes. Read-only produces a report and no edits. |
| `--add-dir ~/.gradle` | **Mandatory** — the wrapper cannot take its own lock without it. |
| `-c sandbox_workspace_write.network_access=true` | **Mandatory** — the skill fetches its catalog from `docs.gradle.org` on every run. |
| `-c shell_environment_policy.inherit=all` | Passes `JAVA_HOME`/`PATH` through so `./gradlew` finds a JDK. |
| `-m gpt-5.6-luna` | Optional. The cheap model, worth setting on a tight quota. |

## 3. Check the result

```bash
./gradlew build          # must succeed
git diff                 # review every change
git status               # sources should appear moved, never deleted
```

Then reset for another run:

```bash
git checkout -- . && git clean -fd
```

## What it should find

<details>
<summary><strong>Spoilers</strong> — the violations planted in <code>sample-carlog/</code></summary>

The project is a two-module Groovy build (root + `:reporting`) around a car-maintenance library. Its build files read as ordinary developer work; none of the comments admit to being a fixture. The violations cluster around build structure and lazy configuration:

- **[Source in the root project](https://docs.gradle.org/current/userguide/best_practices_structuring_builds.html#no_source_in_root)** — `src/main/java/` and `src/test/groovy/` sit beside `settings.gradle`.
- **[No `rootProject.name`](https://docs.gradle.org/current/userguide/best_practices_general.html#name_your_root_project)** — `settings.gradle` never sets it.
- **[Repositories declared per-script](https://docs.gradle.org/current/userguide/best_practices_dependencies.html#set_up_repositories_in_settings)** — `repositories { mavenCentral() }` in both build scripts instead of in settings.
- **[No version catalog](https://docs.gradle.org/current/userguide/best_practices_dependencies.html#use_version_catalogs)** — string-literal GAVs; no `gradle/libs.versions.toml`.
- **[Duplication begging for a convention plugin](https://docs.gradle.org/current/userguide/best_practices_structuring_builds.html#use_convention_plugins)** — `group`, `version`, toolchain and repositories repeated across both build scripts.
- **[Redundant dependency](https://docs.gradle.org/current/userguide/best_practices_dependencies.html#avoid_duplicate_dependencies)** — `api 'org.apache.commons:commons-lang3'` duplicates what `api project(':reporting')` already exposes.
- **[`Provider.get()` at configuration time](https://docs.gradle.org/current/userguide/best_practices_tasks.html#avoid_provider_get_outside_task_action)** — `carLogExtension.logFileName.get()` near the top of `build.gradle`.
- **[`dependsOn` between tasks with actions](https://docs.gradle.org/current/userguide/best_practices_tasks.html#avoid_depends_on)** — `resetMaintenanceLog` declares `dependsOn 'listMaintainedCars'`.
- **[Groovy DSL](https://docs.gradle.org/current/userguide/best_practices_general.html#use_kotlin_dsl)** — every build file is `.gradle` rather than `.gradle.kts`.

The custom tasks are deliberately configuration-cache compatible and `gradle.properties` already enables both caches, so a correct fix never has to disable them.

**Watch the lazy-configuration fix specifically.** Removing the literal `.get()` is not the same as making the wiring lazy — a provider handed to a sink that resolves it eagerly still drops a later reconfiguration. The check that matters is behavioural: reconfigure `carLog.logFileName` after evaluation and see whether the task honours the new value.
</details>

## Notes

[NOTES.md](NOTES.md) has the background: why each flag is mandatory (with the errors you get without it), what a run costs and how to keep it cheap, a representative run for calibration, how Codex actually loads a skill, and troubleshooting.

## Licence

Apache 2.0. The `gradle-best-practices` skill is maintained separately at [gradle/gradle-skills](https://github.com/gradle/gradle-skills).
