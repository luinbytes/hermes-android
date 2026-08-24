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

for method in "${methods[@]}"; do
  class="com.nousresearch.hermes.benchmark.${method}"
  rm -rf benchmark/build/outputs/managed_device_android_test_additional_output \
    benchmark/build/test-results
  if ! ./gradlew --no-daemon --no-parallel --max-workers=1 \
    -Pandroid.testInstrumentationRunnerArguments.class="$class" \
    :benchmark:pixel6Api36BenchmarkReleaseAndroidTest; then
    echo "$method instrumentation failed; retrying only this method."
    rm -rf benchmark/build/outputs/managed_device_android_test_additional_output \
      benchmark/build/test-results
    ./gradlew --no-daemon --no-parallel --max-workers=1 \
      -Pandroid.testInstrumentationRunnerArguments.class="$class" \
      :benchmark:pixel6Api36BenchmarkReleaseAndroidTest
  fi
  destination="$evidence/${method//#/_}"
  mkdir -p "$destination"
  cp -R benchmark/build/outputs/managed_device_android_test_additional_output/. \
    "$destination/"
done

rm -rf benchmark/build/outputs/managed_device_android_test_additional_output
mkdir -p benchmark/build/outputs/managed_device_android_test_additional_output
cp -R "$evidence"/. benchmark/build/outputs/managed_device_android_test_additional_output/
