# Hermes benchmark harness

This module is the credential-free #34 harness foundation. It targets the
non-minified release app package (`com.nousresearch.hermes`) for representative measurement,
supports connected devices, and declares an API 36 Pixel 6 Gradle Managed
Device (`pixel6Api36`). CI runs it for manually dispatched evidence runs and
pull requests into `main`.

## Journeys

`HermesStartupBenchmark` records `StartupTimingMetric` (TTID and TTFD when the
app reports full display) for cold startup and `FrameTimingMetric` for cold and
warm startup.
`HermesSurfaceJourneyBenchmark` provides deterministic fixture journeys for
Atlas/chat, continuous-stream transcript scroll, composer, Files/Artifacts, and
Manage. The benchmark-only fixture reuses the production `Timeline`,
`ArtifactsScreen`, and management header renderers where practical; its
synthetic navigation and Atlas/Files/Manage content are harness coverage, not
claims that the production navigation screens themselves were exercised.
The fixture is present in the `benchmarkRelease` and `nonMinifiedRelease`
source sets because the macrobenchmark and Baseline Profile tasks use distinct
AGP variants; neither source set is part of the production `release` variant.

`BaselineProfileGenerator` exercises startup and the primary fixture surfaces
through the AndroidX Baseline Profile plugin. The generated profile is wired
into release builds and retained with benchmark evidence.

## Deterministic fixtures

`DeterministicFixtures` produces 500 mixed user/assistant/tool/error messages
and a 120-chunk continuous stream at a fixed 25 ms interval. The data contains
no credentials or production content, is stable across runs, and feeds the
benchmark-only fixture built from the production timeline and renderer surfaces.

## Raw evidence format and gate

Each raw result line is represented by `BenchmarkEvidence` and contains:

```json
{
  "benchmark": "cold-start",
  "commit": "<git sha>",
  "device": "Pixel 6",
  "androidApi": 36,
  "toolchain": "<AGP / Gradle / Kotlin>",
  "profileState": "<none|baseline-profile>",
  "repetitions": 5,
  "environment": {"runner": "gmd"},
  "metrics": {"timeToInitialDisplayMs": 0.0}
}
```

`BenchmarkRegression` is the machine-checkable lower-is-better comparator:
candidate values may be at most 10% above the accepted baseline. Missing
metrics fail the comparison. `BenchmarkHarnessTest` verifies the exact 10%
boundary and rejection above it.

Surface memory evidence gates on anonymous and file-backed RSS. The AndroidX
heap-size sample is GC-sensitive and is intentionally not included in the
accepted baseline; the harness still records the stable process RSS signals
that represent resident memory during each journey.

`accepted-baseline.json` records the API 36 Pixel 6 managed-device medians
from the accepted evidence run and its three-pass tie-breaker sample. Pull
requests run the comparator against every numeric benchmark median and fail
when a value regresses by more than 10% or a metric disappears. CI retries the
managed-device task once after an execution failure, then fails if the task
remains red. If a completed sample exceeds the limit, CI runs one confirmation
pass and recomputes the candidate medians across both passes; the 10% limit is
unchanged, so persistent regressions still fail while isolated emulator
variance does not. No physical-device result is inferred from the emulator;
physical reference-device results remain an owner-review gate.
