# Notes

Background for the [README](README.md) walkthrough: why the flags are what they are, what a run costs, how Codex actually loads a skill, and what to do when it goes wrong.

## Why `--add-dir ~/.gradle` is mandatory

`workspace-write` grants write access to the workspace and nothing else. The Gradle user home sits outside it, so without this flag the wrapper cannot even take its own lock before unpacking the distribution:

```
java.io.FileNotFoundException: ~/.gradle/wrapper/dists/gradle-9.5.0-bin/…/gradle-9.5.0-bin.zip.lck
  (Operation not permitted)
        at org.gradle.wrapper.Install.createDist
```

Every `./gradlew` invocation fails this way, so the agent cannot verify its own changes. Verified on this project: with the flag, `BUILD SUCCESSFUL`; without it, the error above.

Pointing `GRADLE_USER_HOME` inside the workspace instead is *not* a workaround — it keeps the run hermetic but forces a full distribution download on every fresh clone, and the daemon still wants a writable real home.

## Why network access is mandatory

The official `gradle-best-practices` skill ships **no embedded catalog**. Its SKILL.md fetches the best-practices pages from `docs.gradle.org` on every run, so the guidance is always current. Codex's `workspace-write` sandbox denies network by default, and without the override the skill loads but cannot reach its own source of truth.

The first `./gradlew` invocation also downloads the Gradle 9.5.0 distribution unless it is already in `~/.gradle/wrapper/dists/`.

## Model choice on a limited quota

A full run is not cheap. The calibration run below was ~740K input tokens, and install Route 2 adds ~140K on top — plus another ~70K if you re-run it and rediscover that the installer refuses to overwrite. On a free ChatGPT plan that is enough to exhaust the Codex allowance mid-run:

```
You've hit your usage limit. To continue using Codex and get access to GPT-5.3-Codex,
start a free trial of Plus today, or try again at <date>.
```

Two levers, largest first:

- **Pick the cheap model.** `-m gpt-5.6-luna`. Codex's own model catalog calls Luna the *"fast and affordable agentic coding model"* — it is where GPT-5.4 Mini was retired to — and lists it as available on every plan tier, free included. Terra is the *"balanced … everyday"* tier, Sol and Astra the expensive ones. Stack `-c model_reasoning_effort="low"` on top: Terra and Luna both default to `medium`.
- **Install with Route 1.** `npx skills add` is a file copy and costs no model tokens at all. Route 2 is worth running once to watch `skill-installer` work; re-running it while you iterate on the Gradle run is pure overhead.

Codex publishes no per-model quota weights, so there is no honest way to put a number on the saving. Nor is Luna's result calibrated here — the representative run below is Terra at `medium`, and how many violations a smaller model finds and fixes is exactly the kind of thing worth measuring before you rely on it.

To see which model a run actually used, read the session rollout:

```bash
grep -o '"model":"[^"]*"' ~/.codex/sessions/*/*/*/rollout-*.jsonl | tail -1
```

## A representative run

For calibration, one run of exactly the README's command (`gpt-5.6-terra`, Codex CLI 0.154.0, 13 shell commands, ~740K input tokens of which ~660K cached, 7K output):

- Fetched seven `best_practices_*.html` pages from `docs.gradle.org` — the live catalog, working as designed.
- Applied: wrapper upgrade to 9.7.1 with `distributionSha256Sum`, `rootProject.name`, repositories centralised in settings with `FAIL_ON_PROJECT_REPOS`, a version catalog, UTF-8 encoding, lazy provider wiring with path sensitivity, `dependsOn` removal.
- Declined: Kotlin DSL conversion, convention plugins, and moving root sources into a subproject — reported explicitly as "larger structural recommendations" left alone.
- `./gradlew build` passed, 32 tests, all five source files untouched.

Where it draws the line between "apply" and "recommend" moves with the prompt. Asking it to re-examine before concluding, and not to stop while any identified issue is unaddressed, pushes it into the structural fixes as well.

## How Codex loads a skill

Worth knowing when you are debugging a run that ignored the skill, or building tooling around one.

Codex has **no dedicated skill tool**. Skill names and descriptions reach the model in its preamble; "loading" a skill is the model choosing to read the file with an ordinary shell command. A run that used the skill looks like this in `--json` output:

```
command_execution  /bin/zsh -lc "sed -n '1,240p' ../.agents/skills/gradle-best-practices/SKILL.md"
command_execution  /bin/zsh -lc "sed -n '241,520p' ../.agents/skills/gradle-best-practices/SKILL.md"
command_execution  /bin/zsh -lc "rg --files -g 'settings.gradle' -g 'build.gradle' ..."
```

So to confirm a skill was actually used, capture the event stream and look for a shell command touching its `SKILL.md` path:

```bash
codex exec --json ... > run.jsonl
grep -c 'gradle-best-practices/SKILL.md' run.jsonl
```

There is no tool-call name to match on, which matters if you are porting a skill-activation check from an agent that has one.

Skill discovery itself is filesystem-based and relative to Codex's working directory, walking up to the project root. `codex debug prompt-input "hi"` renders the prompt Codex *would* send — `### Skill roots` and `### Available skills` included — with no model call and no quota spent.

## Troubleshooting

**Two skills with the same name.** Route 2 writes to `~/.codex/skills/`, Route 1 to `./.agents/skills/`. Codex reads every root at once and will list `gradle-best-practices` twice, with different descriptions if the two copies are different versions. Delete whichever you do not want:

```bash
ls -d ~/.codex/skills/gradle-* ~/.agents/skills/gradle-* \
      .agents/skills/gradle-* .codex/skills/gradle-* 2>/dev/null
```

**Installing without Node, or pinning a ref.** Neither route covers it; clone instead:

```bash
git clone --depth 1 https://github.com/gradle/gradle-skills /tmp/gradle-skills
mkdir -p .agents/skills
cp -R /tmp/gradle-skills/skills/gradle-best-practices .agents/skills/
```

Same destination as Route 1. Also the way to update an install that Route 2 refuses to overwrite.

**A project-local skill is invisible.** Discovery is relative to Codex's working directory, walking up to the project root — so a skill in `./.agents/skills/` disappears the moment you run Codex from outside that tree. `codex debug prompt-input "hi"` prints the roots actually in play, for free.

**A skill you just installed is not being used.** It becomes available on the *next* turn. In `codex exec`, that means the next invocation — installing and using a skill in one non-interactive run does not work.

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

Overriding `CODEX_HOME` alone is not enough — `~/.agents/skills/` is still read, which is why `HOME` is overridden too. Neither override hides a project-local `./.agents/skills/` or `./.codex/skills/`: those are found relative to the working directory, so run from a directory that has neither.
