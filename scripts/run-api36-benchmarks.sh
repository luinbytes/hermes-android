#!/usr/bin/env bash
set -euo pipefail

methods=(
  "HermesStartupBenchmark#coldStartupReportsTtidTtfdAndFrames"
  "HermesStartupBenchmark#warmStartupReportsFrames"
  "HermesSurfaceJourneyBenchmark#atlasJourney"
  "HermesSurfaceJourneyBenchmark#chatContinuousStreamJourney"
  "HermesSurfaceJourneyBenchmark#transcriptScrollDuringContinuousStreamJourney"
  "HermesSurfaceJourneyBenchmark#composerJourney"
  "HermesSurfaceJourneyBenchmark#filesJourney"
  "HermesSurfaceJourneyBenchmark#artifactsJourney"
  "HermesSurfaceJourneyBenchmark#manageJourney"
)
evidence="${RUNNER_TEMP:?}/hermes-benchmark-methods"
rm -rf "$evidence"
mkdir -p "$evidence"

has_result() {
  local expected=$1 file
  while IFS= read -r -d '' file; do
    jq -e --arg expected "$expected" \
      'any(.benchmarks[]?; .name == $expected)' "$file" >/dev/null && return 0
  done < <(find benchmark/build/outputs/managed_device_android_test_additional_output \
    -type f -name '*benchmarkData.json' -print0 2>/dev/null)
  return 1
}

for method in "${methods[@]}"; do
  class="com.nousresearch.hermes.benchmark.${method}"
  benchmark_name=${method#*#}
  passed=false
  for attempt in 1 2; do
    rm -rf benchmark/build/outputs/managed_device_android_test_additional_output \
      benchmark/build/test-results
    if ./gradlew --no-daemon --no-parallel --max-workers=1 \
      -Pandroid.testInstrumentationRunnerArguments.class="$class" \
      :benchmark:pixel6Api36BenchmarkReleaseAndroidTest && \
      has_result "$benchmark_name"; then
      passed=true
      break
    fi
    if (( attempt == 1 )); then
      echo "$method did not produce valid benchmark evidence; retrying only this method."
    fi
  done
  if [[ $passed != true ]]; then
    echo "$method failed twice or did not produce valid benchmark evidence." >&2
    exit 1
  fi
  destination="$evidence/${method//#/_}"
  mkdir -p "$destination"
  cp -R benchmark/build/outputs/managed_device_android_test_additional_output/. \
    "$destination/"
done

rm -rf benchmark/build/outputs/managed_device_android_test_additional_output
mkdir -p benchmark/build/outputs/managed_device_android_test_additional_output
cp -R "$evidence"/. benchmark/build/outputs/managed_device_android_test_additional_output/
