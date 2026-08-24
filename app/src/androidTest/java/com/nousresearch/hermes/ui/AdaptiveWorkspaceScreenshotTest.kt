package com.nousresearch.hermes.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.core.layout.WindowSizeClass
import com.nousresearch.hermes.domain.TimelineItem
import com.nousresearch.hermes.domain.ToolState
import com.nousresearch.hermes.ui.theme.HermesTheme
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveWorkspaceScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun captureCompactProductionShellFrame() = captureProductionShellMatrixFrame("compact")

    @Test
    fun captureExpandedProductionShellFrame() = captureProductionShellMatrixFrame("expanded")

    @Test
    fun captureFoldProductionShellFrame() = captureProductionShellMatrixFrame("fold")

    private fun captureProductionShellMatrixFrame(mode: String) {
        // The Pixel 2 runs the compact golden; wide goldens run on the Pixel Tablet.
        if (mode != "compact") {
            assumeTrue(
                "Wide adaptive shell goldens require the API 36 tablet",
                Build.VERSION.SDK_INT >= 29 &&
                    InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.smallestScreenWidthDp >= 600,
            )
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val goldenSize = when (mode) {
            "expanded" -> 1280 to 800
            "fold" -> 1200 to 800
            else -> 360 to 800
        }
        val configuration = when (mode) {
            "expanded" -> adaptiveWorkspaceConfiguration(WindowSizeClass(1200, 800))
            "fold" -> adaptiveWorkspaceConfiguration(
                WindowSizeClass(1200, 800),
                verticalHinge = AdaptiveHingeBounds(590.dp, 0.dp, 610.dp, 800.dp),
            )
            else -> adaptiveWorkspaceConfiguration(WindowSizeClass(360, 800))
        }
        val tool = TimelineItem.Tool(
            id = "tool-7",
            name = "terminal",
            state = ToolState.COMPLETE,
            summary = "Command completed",
            detail = """{"output":"feature parity check: passed","exit_code":0}""",
        )

        compose.setContent {
            HermesTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 1f, fontScale = 1.3f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    Surface(
                        Modifier.size(goldenSize.first.dp, goldenSize.second.dp).testTag("golden-root"),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        AdaptiveWorkspaceShell(
                            configuration = configuration,
                            destination = "conversation/session-42",
                            destinations = listOf("conversation/session-42"),
                            isListDestination = { false },
                            paneModifier = { _, _ -> Modifier.fillMaxSize() },
                            expandedNavigation = {
                                Column(
                                    Modifier
                                        .width(330.dp)
                                        .fillMaxHeight()
                                        .padding(20.dp),
                                ) {
                                    Text("HERMES", style = MaterialTheme.typography.titleLarge)
                                    Text("SESSIONS", style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.padding(8.dp))
                                    Text("Adaptive shell review", style = MaterialTheme.typography.bodyMedium)
                                }
                                HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                            },
                            modifier = Modifier.fillMaxSize(),
                            supportingPaneKey = "tool-output/tool-7",
                            supportingPane = {
                                ToolSupportingPane(tool = tool, onClose = {})
                            },
                        ) { _, compact ->
                            var draft by remember { mutableStateOf("Keep the draft while the window changes") }
                            Column(Modifier.fillMaxSize().padding(20.dp).testTag("detail-pane")) {
                                Text("ADAPTIVE PRODUCT SHELL", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    if (compact) "Compact conversation" else "Persistent conversation detail",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.weight(1f))
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Message Hermes") },
                                )
                            }
                        }
                    }
                }
            }
        }

        compose.waitForIdle()
        val outputDir = File(instrumentation.targetContext.getExternalFilesDir(null), "issue17")
        assertTrue(outputDir.mkdirs() || outputDir.isDirectory)
        val output = File(outputDir, "issue17-shell-$mode.png")
        val actual = compose.onNodeWithTag("golden-root").captureToImage().asAndroidBitmap()
        FileOutputStream(output).use { stream ->
            assertTrue(actual.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue(output.length() > 0)
        val recordGoldens = InstrumentationRegistry.getArguments()
            .getString("recordGoldens")
            ?.toBooleanStrictOrNull()
            ?: false
        if (!recordGoldens) {
            val expected = instrumentation.context.assets.open("goldens/issue17-shell-$mode.png").use {
                requireNotNull(BitmapFactory.decodeStream(it))
            }
            assertScreenshotsMatch(expected, actual)
        }
    }

    private fun assertScreenshotsMatch(expected: Bitmap, actual: Bitmap) {
        assertEquals("Screenshot width changed", expected.width, actual.width)
        assertEquals("Screenshot height changed", expected.height, actual.height)
        val expectedPixels = IntArray(expected.width * expected.height)
        val actualPixels = IntArray(expected.width * expected.height)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        val changed = expectedPixels.indices.count { index ->
            val expectedPixel = expectedPixels[index]
            val actualPixel = actualPixels[index]
            abs((expectedPixel shr 16 and 0xff) - (actualPixel shr 16 and 0xff)) > 2 ||
                abs((expectedPixel shr 8 and 0xff) - (actualPixel shr 8 and 0xff)) > 2 ||
                abs((expectedPixel and 0xff) - (actualPixel and 0xff)) > 2
        }
        val allowedChangedPixels = expectedPixels.size / 1000 + 32
        assertTrue("Screenshot changed in $changed pixels", changed <= allowedChangedPixels)
    }
}
