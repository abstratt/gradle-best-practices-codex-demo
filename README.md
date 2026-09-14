# Gradle Best Practices with Codex

A self-contained demo: run the official [`gradle-best-practices`](https://github.com/gradle/gradle-skills) skill against a small Gradle build that deliberately violates a cluster of documented best practices, using [Codex](https://developers.openai.com/codex/).

Nothing in this repository registers or bundles a skill. `sample-carlog/` is a plain Gradle project. You install the skill separately, from the official Gradle skills repository, and Codex picks it up from your user-level skill directory.

## Prerequisites

| | |
|---|---|
| **JDK** | 17 or later on `PATH` (the build targets a Java 21 toolchain and will provision one if needed) |
| **Codex CLI** | `codex --version`. If you installed the ChatGPT desktop app, the binary ships inside it at `/Applications/ChatGPT.app/Contents/Resources/codex` and is *not* on `PATH` — symlink it: `ln -s /Applications/ChatGPT.app/Contents/Resources/codex /opt/homebrew/bin/codex` |
| **Node** | for `npx` (`brew install node`) |
| **Network** | required at run time — see [Why network access is mandatory](#why-network-access-is-mandatory) |

## 1. Install the skill

```bash
npx skills add gradle/gradle-skills --skill gradle-best-practices
```

This installs to `~/.agents/skills/gradle-best-practices/`, the vendor-neutral skill directory that Codex reads. One copy serves every agent that honours the convention.

<details>
<summary>Alternative: install without <code>npx</code></summary>

```bash
git clone --depth 1 https://github.com/gradle/gradle-skills /tmp/gradle-skills
mkdir -p ~/.codex/skills
cp -R /tmp/gradle-skills/skills/gradle-best-practices ~/.codex/skills/
```

Codex reads both `~/.agents/skills/` and `$CODEX_HOME/skills/` (default `~/.codex/skills/`). Use one or the other — installing to both makes two skills with the identical name `gradle-best-practices` visible at once, with no way to tell Codex which you meant.
</details>

### Confirm Codex sees it

```bash
codex exec --sandbox read-only \
  "List every skill available to you with its exact name. Do nothing else."
```

`gradle-best-practices` should appear in the list. If it does not, the install landed somewhere Codex does not scan.

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

Run it from inside `sample-carlog/`, not from the repository root, so the agent's workspace is the Gradle project alone and it does not read this README's [findings list](#what-it-should-find) as a hint.

Expect roughly 15–25 shell commands and a few minutes. Drop `exec` and the prompt to drive it interactively instead.

### What the flags are for

| Flag | Why |
|---|---|
| `--sandbox workspace-write` | The skill's job is to *apply* fixes. Read-only produces a report and no edits. |
| `--add-dir ~/.gradle` | **Mandatory.** See below. |
| `-c sandbox_workspace_write.network_access=true` | **Mandatory.** See below. |
| `-c shell_environment_policy.inherit=all` | Passes `JAVA_HOME`/`PATH` through to the sandboxed shell so `./gradlew` finds a JDK. |

### Why `--add-dir ~/.gradle` is mandatory

`workspace-write` grants write access to the workspace and nothing else. The Gradle user home sits outside it, so without this flag the wrapper cannot even take its own lock before unpacking the distribution:

```
java.io.FileNotFoundException: ~/.gradle/wrapper/dists/gradle-9.5.0-bin/…/gradle-9.5.0-bin.zip.lck
  (Operation not permitted)
        at org.gradle.wrapper.Install.createDist
```

Every `./gradlew` invocation fails this way, so the agent cannot verify its own changes. Verified on this project: with the flag, `BUILD SUCCESSFUL`; without it, the error above.

Pointing `GRADLE_USER_HOME` inside the workspace instead is *not* a workaround — it keeps the run hermetic but forces a full distribution download on every fresh clone, and the daemon still wants a writable real home.

### Why network access is mandatory

The official `gradle-best-practices` skill ships **no embedded catalog**. Its SKILL.md fetches the best-practices pages from `docs.gradle.org` on every run, so the guidance is always current. Codex's `workspace-write` sandbox denies network by default, and without the override the skill loads but cannot reach its own source of truth.

The first `./gradlew` invocation also downloads the Gradle 9.5.0 distribution unless it is already in `~/.gradle/wrapper/dists/`.

### A representative run

For calibration, one run of exactly the command above (`gpt-5.6-terra`, Codex CLI 0.154.0, 13 shell commands, ~740K input tokens of which ~660K cached, 7K output):

- Fetched seven `best_practices_*.html` pages from `docs.gradle.org` — the live catalog, working as designed.
- Applied: wrapper upgrade to 9.7.1 with `distributionSha256Sum`, `rootProject.name`, repositories centralised in settings with `FAIL_ON_PROJECT_REPOS`, a version catalog, UTF-8 encoding, lazy provider wiring with path sensitivity, `dependsOn` removal.
- Declined: Kotlin DSL conversion, convention plugins, and moving root sources into a subproject — reported explicitly as "larger structural recommendations" left alone.
- `./gradlew build` passed, 32 tests, all five source files untouched.

Where it draws the line between "apply" and "recommend" moves with the prompt. Asking it to re-examine before concluding, and not to stop while any identified issue is unaddressed, pushes it into the structural fixes as well.

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

## How Codex loads a skill

Worth knowing when you are debugging a run that ignored the skill, or building tooling around one.

Codex has **no dedicated skill tool**. Skill names and descriptions reach the model in its preamble; "loading" a skill is the model choosing to read the file with an ordinary shell command. A run that used the skill looks like this in `--json` output:

```
command_execution  /bin/zsh -lc "sed -n '1,240p' ~/.agents/skills/gradle-best-practices/SKILL.md"
command_execution  /bin/zsh -lc "sed -n '241,520p' ~/.agents/skills/gradle-best-practices/SKILL.md"
command_execution  /bin/zsh -lc "rg --files -g 'settings.gradle' -g 'build.gradle' ..."
```

So to confirm a skill was actually used, capture the event stream and look for a shell command touching its `SKILL.md` path:

```bash
codex exec --json ... > run.jsonl
grep -c 'gradle-best-practices/SKILL.md' run.jsonl
```

There is no tool-call name to match on, which matters if you are porting a skill-activation check from an agent that has one.

## Troubleshooting

**Two skills with the same name.** `npx skills add` writes to `~/.agents/skills/`; a manual copy may sit in `~/.codex/skills/`. Codex reads both and will list `gradle-best-practices` twice with different descriptions. Delete one.

**The skill loads but reports nothing.** Almost always network: it fetched no catalog. Confirm `sandbox_workspace_write.network_access=true` is set.

**`./gradlew` fails with no JDK.** The sandboxed shell did not inherit your environment. Add `-c shell_environment_policy.inherit=all`, or set `org.gradle.java.home` in `gradle.properties`.

**`Operation not permitted` on a file under `~/.gradle`.** The Gradle user home is not writable from the sandbox. Add `--add-dir ~/.gradle`.

**Checking sandbox behaviour without burning a model run.** `codex sandbox` runs a single command under the same seatbelt policy — useful for proving a build works before you let an agent loose on it. Note it defaults to **read-only** regardless of your `config.toml`, so set the mode explicitly or every write silently fails:

```bash
codex sandbox -c sandbox_mode="workspace-write" \
  -c 'sandbox_workspace_write.writable_roots=["'$HOME'/.gradle"]' \
  -c sandbox_workspace_write.network_access=true \
  -c shell_environment_policy.inherit=all \
  ./gradlew build
```

**Isolating a run from your installed skills.** To test one skill with nothing else visible, point Codex at a throwaway home:

```bash
mkdir -p /tmp/ch/skills && ln -s ~/.codex/auth.json /tmp/ch/auth.json
cp -R ~/.agents/skills/gradle-best-practices /tmp/ch/skills/
HOME=/tmp/fakehome CODEX_HOME=/tmp/ch codex exec ...
```

Overriding `CODEX_HOME` alone is not enough — `~/.agents/skills/` is still read, which is why `HOME` is overridden too.

## Licence

Apache 2.0. The `gradle-best-practices` skill is maintained separately at [gradle/gradle-skills](https://github.com/gradle/gradle-skills).
