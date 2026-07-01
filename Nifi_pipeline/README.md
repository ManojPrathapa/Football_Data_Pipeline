# NiFi ETL Fuzzer — drop-in for your existing project

Matches your actual layout (flat files, no `src/`, no package, no Maven) — same as your
existing `NiFiOrchestrator.java`. Just `javac`/`java`.

## Where to put these files

Your current structure:

```
FOOTBALL_DATA_PIPELINE/
  Nifi_pipeline/
    imgs/
    Source/                  <- GetFile watches this. Fuzzer writes AND reads here.
    Target/
    NiFiOrchestrator.java    <- gets REPLACED by the new one below
    Football_Nifi_final.json
    NiFi_Flow_Original.json
    ...
```

Drop all 13 `.java` files below **directly into `Nifi_pipeline/`**, next to your existing
`NiFiOrchestrator.java` (overwrite it) and next to `Source/` and `Target/`:

```
Nifi_pipeline/
  BulletinMonitor.java
  CoverageTracker.java
  CsvSchema.java
  FuzzEngine.java
  FuzzLogger.java
  MiniJson.java
  Mutators.java
  NiFiOrchestrator.java     <- replaces yours (same auth/start/stop/status + new fuzz/stopfuzz)
  ProcessorRegistry.java
  QueueManager.java
  Seed.java
  SeedCorpusBuilder.java
  SeedWatcher.java
```

No subfolders, no package declarations — same flat default-package style your project already
uses, so your existing `javac NiFiOrchestrator.java` workflow still works, just now compiling
all 13 files together.

Delete your old `NiFiOrchestrator.class` and `NiFiOrchestrator$1.class` first (stale bytecode),
then:

```powershell
cd Nifi_pipeline
javac *.java
java NiFiOrchestrator
```

Then just type `fuzz`. That's your "click start" — one command, everything else automatic.

## Answering your three questions directly

### "Will it fuzz all 100 CSVs I drop in?"

Yes, and it's genuinely dynamic now — not a one-time load:

- **At startup**, `fuzz` scans every `*.csv` already sitting in `Source/` and pulls seed rows
  from all of them (bucketed by `type`, capped at 15 rows per type total across all files so
  100 huge files don't blow up memory — you don't need more than a handful of real examples
  per event type, the mutation engine generates the variety from there).
- **While running**, `SeedWatcher` keeps watching `Source/` the entire time. Drop in 1 file or
  100 at once — each one gets detected (via Java's filesystem watch API, near-instant) and its
  rows get folded into the live seed pool within about a second, no restart needed.

### "Won't the original files get consumed/deleted and leave nothing to fuzz from?"

That's exactly why the corpus lives **in memory**, not re-read from disk each time. The instant
a CSV is scanned (at startup) or detected (mid-run), its rows are copied into `Seed` objects and
also safety-copied into `fuzz_incoming/`. So even though `GetFile` deletes the original from
`Source/` after ingesting it (your `Keep Source File = false` setting, unchanged), the fuzzer
never re-reads the original — it already has what it needs. The pipeline eating your real files
is expected and harmless to the fuzzer.

One added safety: `fuzz` now stops the pipeline for ~500ms at the very start, scans `Source/`
for the initial corpus, *then* starts the pipeline — so `GetFile` can't win a race against the
very first scan.

### "Isn't this an infinite loop?"

Yes, on purpose — you said that's fine, you'll `stopfuzz` when you're done. Two things keep it
from running away on you over a long session:
- **Corpus cap**: 500 seeds max. Once hit, the lowest-value seeds (fewest new-coverage hits,
  most-already-tried) get evicted to make room. Doesn't affect fuzzing quality, just memory.
- **Files on disk don't pile up**: each fuzzed CSV is one row, and `GetFile` deletes it right
  after ingesting (same as your original files) — so `Source/` doesn't fill up either.

## Real-time log viewing (confirmed, no change needed)

Every log write already calls `.flush()` immediately — there's no buffering delay. Tail any of
them live while the fuzzer runs:

```powershell
Get-Content fuzz_logs\fuzz_run_<runId>.log -Wait -Tail 20
Get-Content fuzz_logs\crashes_<runId>.log -Wait -Tail 20
```

You'll see each line appear the moment it's written, not after you stop the fuzzer.

## Output layout (all new folders, won't touch your existing `Source`/`Target`)

```
fuzz_seeds/     one CSV per seed pulled at startup
fuzz_incoming/  safety copies of every CSV you drop in mid-run (survives even if GetFile deletes the original)
fuzz_logs/      fuzz_run_<id>.log, crashes_<id>.log, coverage_<id>.log
fuzz_crashes/   one standalone CSV per FAIL case, ready to re-feed/debug in isolation
fuzz_reports/   coverage_<id>.json - refreshed every 25 iterations and on stopfuzz
```

## Before you run — one thing to verify

`FUZZ_SOURCE_DIR = "Source"` (relative path). This works as-is **if** you run `java
NiFiOrchestrator` from inside `Nifi_pipeline/` — which your VS Code layout suggests you do,
since `Source/` sits right next to `NiFiOrchestrator.java`. If NiFi runs in a Docker container
and this folder is bind-mounted to `/opt/nifi/nifi-current/data`, this relative path already
resolves correctly on the host side. If it doesn't work, the only fix needed is changing that
one constant to the correct absolute host path.

`TARGET_PROCESS_GROUP_ID` is copied from your file unchanged.

## Same honest limitations as before

- **Coverage is throughput-based** (which processors got flowfiles), not JaCoCo/Jazzer bytecode
  coverage — NiFi runs in its own JVM, cross-process bytecode instrumentation isn't possible.
- **Bulletin-to-injection correlation is best-effort by timing window** (~900ms), not
  provenance-exact. Fine for nonstop fuzzing; if you need flowfile-exact attribution later, the
  next step is the `/nifi-api/provenance` query endpoint — say the word.
- **Pure JDK, no Jazzer/JQF library.** If you specifically want literal JQF+Zest bytecode
  coverage of your *own* Java code (e.g. a custom processor JAR), that's a separate,
  Maven-plugin-driven harness — different from fuzzing the running pipeline. Let me know if you
  want that layered in too.
