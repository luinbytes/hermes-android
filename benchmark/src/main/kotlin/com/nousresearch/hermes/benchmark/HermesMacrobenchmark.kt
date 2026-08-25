package com.nousresearch.hermes.benchmark

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE_NAME = "com.nousresearch.hermes"
private const val FIXTURE_ACTIVITY_NAME = "com.nousresearch.hermes.benchmark.BenchmarkFixtureActivity"
private const val FIXTURE_RESET_EXTRA = "hermes.benchmark.fixture_reset"

@RunWith(AndroidJUnit4::class)
class HermesStartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupReportsTtidTtfdAndFrames() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            device.waitForIdle()
        }
    }

    @Test
    fun warmStartupReportsFrames() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait(
                Intent().setComponent(ComponentName(TARGET_PACKAGE_NAME, FIXTURE_ACTIVITY_NAME))
                    .putExtra(FIXTURE_RESET_EXTRA, System.nanoTime()),
            )
            device.waitForIdle()
        }
    }
}

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class HermesSurfaceJourneyBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun atlasJourney() = measureSurface("Atlas") {}

    @Test
    fun chatContinuousStreamJourney() = measureSurface("Chats", waitForIdleBeforeJourney = false) {
        SystemClock.sleep(3_200)
    }

    @Test
    fun transcriptScrollDuringContinuousStreamJourney() = measureSurface("Chats", waitForIdleBeforeJourney = false) {
        repeat(8) {
            swipe(displayWidth / 2, displayHeight * 3 / 4, displayWidth / 2, displayHeight / 4, 24)
            SystemClock.sleep(250)
        }
    }

    @Test
    fun composerJourney() = measureSurface("Chats") {
        val composer = requireNotNull(
            wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000L)
                ?: wait(Until.findObject(By.descContains("benchmark-composer")), 1_000L),
        ) {
            "Benchmark composer field not found"
        }
        val expectedText = "Deterministic benchmark composer input"
        composer.click()
        composer.setText(expectedText)
        val accepted = wait(Until.findObject(By.textContains(expectedText)), 5_000L)
            ?: wait(Until.findObject(By.descContains("benchmark-draft:$expectedText")), 5_000L)
        requireNotNull(accepted) {
            "Benchmark composer field did not accept deterministic input"
        }
        waitForIdle()
    }

    @Test
    fun filesJourney() = measureSurface("Files") {}

    @Test
    fun artifactsJourney() = measureSurface("Artifacts") {}

    @Test
    fun manageJourney() = measureSurface("Manage") {}

    private fun measureSurface(
        label: String,
        waitForIdleBeforeJourney: Boolean = true,
        journey: UiDevice.() -> Unit,
    ) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE_NAME,
            metrics = listOf(
                FrameTimingMetric(),
                MemoryUsageMetric(
                    mode = MemoryUsageMetric.Mode.Last,
                    subMetrics = listOf(
                        MemoryUsageMetric.SubMetric.RssAnon,
                        MemoryUsageMetric.SubMetric.RssFile,
                    ),
                ),
            ),
            iterations = 3,
            startupMode = StartupMode.WARM,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait(
                Intent().setComponent(ComponentName(TARGET_PACKAGE_NAME, FIXTURE_ACTIVITY_NAME))
                    .putExtra(FIXTURE_RESET_EXTRA, System.nanoTime()),
            )
            if (label != "Chats") device.clickText(label)
            if (waitForIdleBeforeJourney) device.waitForIdle() else SystemClock.sleep(100)
            device.journey()
            device.waitForIdle()
        }
    }
}

private fun UiDevice.clickText(text: String) {
    requireNotNull(
        wait(Until.findObject(By.desc(text)), 2_000L)
            ?: wait(Until.findObject(By.textContains(text)), 13_000L),
    ) {
        "Benchmark fixture surface not found: $text"
    }.click()
    waitForIdle()
}
