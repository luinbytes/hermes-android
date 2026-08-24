package com.nousresearch.hermes.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawText
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nousresearch.hermes.data.HermesState
import com.nousresearch.hermes.protocol.StarmapEdge
import com.nousresearch.hermes.protocol.StarmapNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private enum class StarmapView { MAP, SKILLS }

internal data class StarmapPosition(val id: String, val x: Float, val y: Float)

@Composable
internal fun StarmapScreen(
    state: HermesState,
    profile: String,
    onRefresh: (String) -> Unit,
    onOpenNode: (String, String) -> Unit,
    onCloseNode: () -> Unit,
    onUpdateNode: (String, String, String) -> Unit,
    onDeleteNode: (String, String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var query by remember(profile) { mutableStateOf("") }
    var view by rememberSaveable(profile) { mutableStateOf(StarmapView.MAP) }
    var focusedNodeId by remember(profile) { mutableStateOf<String?>(null) }
    var deleteNodeId by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(profile) { onRefresh(profile) }

    val graph = state.starmap.takeIf { state.starmapProfile == profile }
    val matches = remember(graph, query) {
        graph?.nodes.orEmpty().filter { starmapMatches(it, query) }
            .sortedWith(compareByDescending<StarmapNode> { it.pinned }.thenByDescending { it.useCount }.thenBy { it.label })
    }
    val skills = remember(graph, query) { matches.filter { it.kind.equals("skill", ignoreCase = true) } }
    val matchesById = remember(matches) { matches.associateBy(StarmapNode::id) }

    Column(modifier.fillMaxSize()) {
        ManagementHeader("STARMAP", "Remote learning graph / profile $profile", state.starmapLoading, { onRefresh(profile) }, onBack)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Text(
                "Explore memory as a map. Pinch to zoom, drag to move, or search to focus a node.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(200) },
            label = { Text("Search memory graph") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { matches.firstOrNull()?.let { focusedNodeId = it.id } }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = view == StarmapView.MAP,
                onClick = { view = StarmapView.MAP },
                label = { Text("Star map") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = view == StarmapView.SKILLS,
                onClick = { view = StarmapView.SKILLS },
                label = { Text("Skills (${graph?.nodes.orEmpty().count { it.kind.equals("skill", true) }})") },
                modifier = Modifier.weight(1f),
            )
        }
        if (query.isNotBlank() && matches.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(matches.take(12), key = StarmapNode::id) { node ->
                    FilterChip(
                        selected = focusedNodeId == node.id,
                        onClick = { view = StarmapView.MAP; focusedNodeId = node.id },
                        label = { Text(node.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        state.starmapNotice?.let {
            Text(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary)
        }
        state.starmapError?.let { ManagementError(it) }

        when {
            state.starmapLoading && graph == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            graph == null || graph.nodes.isEmpty() -> Text(
                "This Hermes profile has no learning nodes.",
                modifier = Modifier.padding(32.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            view == StarmapView.MAP -> StarmapCanvas(
                nodes = graph.nodes,
                edges = graph.edges,
                matchingIds = matchesById.keys,
                queryActive = query.isNotBlank(),
                focusedNodeId = focusedNodeId,
                onFocusNode = { focusedNodeId = it },
                onOpenNode = { onOpenNode(profile, it) },
                modifier = Modifier.fillMaxSize(),
            )
            skills.isEmpty() -> Text(
                if (query.isBlank()) "This profile has no skills." else "No skills match this search.",
                modifier = Modifier.padding(32.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> SkillList(skills, graph.edges) {
                confirmDiscard = false
                onOpenNode(profile, it)
            }
        }
    }

    val selectedId = state.starmapNodeId
    val detail = state.starmapNode.takeIf { selectedId != null }
    if (selectedId != null) {
        var draft by remember(selectedId, detail?.content) { mutableStateOf(detail?.content.orEmpty()) }
        val dirty = detail != null && draft != detail.content
        fun requestClose() {
            if (dirty) confirmDiscard = true else onCloseNode()
        }
        AlertDialog(
            onDismissRequest = ::requestClose,
            title = { Text(detail?.label?.take(200) ?: "LEARNING NODE") },
            text = {
                if (detail == null && state.starmapLoading) {
                    CircularProgressIndicator()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${detail?.kind.orEmpty().uppercase()} / profile $profile", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.take(262_144) },
                            label = { Text("Content") },
                            minLines = 9,
                            maxLines = 18,
                            enabled = detail != null && !state.starmapLoading,
                            supportingText = { Text("${draft.length}/262144 characters") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        state.starmapError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onUpdateNode(profile, selectedId, draft) },
                    enabled = detail != null && dirty && !state.starmapLoading,
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleteNodeId = selectedId }, enabled = detail != null && !state.starmapLoading) {
                        Icon(Icons.Outlined.Delete, null)
                        Text("Remove")
                    }
                    TextButton(onClick = ::requestClose) { Text("Close") }
                }
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("DISCARD LEARNING EDITS?") },
            text = { Text("Unsaved changes to this remote learning node will be lost.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onCloseNode() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    deleteNodeId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteNodeId = null },
            title = { Text("REMOVE LEARNING NODE?") },
            text = { Text("Hermes will archive a skill or remove a memory node from profile $profile. This cannot be undone from Android.") },
            confirmButton = { TextButton(onClick = { deleteNodeId = null; onDeleteNode(profile, id) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleteNodeId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StarmapCanvas(
    nodes: List<StarmapNode>,
    edges: List<StarmapEdge>,
    matchingIds: Set<String>,
    queryActive: Boolean,
    focusedNodeId: String?,
    onFocusNode: (String?) -> Unit,
    onOpenNode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val positions = remember(nodes) { starmapLayout(nodes) }
    val positionsById = remember(positions) { positions.associateBy(StarmapPosition::id) }
    var targetScale by remember { mutableFloatStateOf(1f) }
    var targetPan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val hitRadius = with(density) { 28.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        targetScale = (targetScale * zoomChange).coerceIn(0.55f, 4f)
        targetPan += panChange
    }
    val motion: AnimationSpec<Float> = if (transformState.isTransformInProgress) snap() else tween(380)
    val scale by animateFloatAsState(targetScale, motion, label = "starmap-scale")
    val panX by animateFloatAsState(targetPan.x, motion, label = "starmap-pan-x")
    val panY by animateFloatAsState(targetPan.y, motion, label = "starmap-pan-y")
    val reveal by animateFloatAsState(if (viewport == IntSize.Zero) 0f else 1f, tween(550), label = "starmap-reveal")
    val focusedNode = remember(nodes, focusedNodeId) { nodes.firstOrNull { it.id == focusedNodeId } }

    fun resetMap() {
        targetScale = 1f
        targetPan = Offset.Zero
        onFocusNode(null)
    }
    LaunchedEffect(focusedNodeId, viewport) {
        val position = positionsById[focusedNodeId] ?: return@LaunchedEffect
        val radius = min(viewport.width, viewport.height) * 0.36f
        targetScale = maxOf(targetScale, 1.7f)
        targetPan = -Offset(position.x, position.y) * radius * targetScale
    }

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            val primary = MaterialTheme.colorScheme.primary
            val secondary = MaterialTheme.colorScheme.secondary
            val outline = MaterialTheme.colorScheme.outline
            val surface = MaterialTheme.colorScheme.surfaceVariant
            val selected = MaterialTheme.colorScheme.tertiary
            val labelColor = MaterialTheme.colorScheme.onSurface
            Canvas(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(surface, RoundedCornerShape(12.dp))
                    .onSizeChanged { viewport = it }
                    .semantics {
                        contentDescription = "Interactive memory star map with ${nodes.size} nodes and ${edges.size} connections"
                    }
                    .pointerInput(positions, scale, panX, panY) {
                        detectTapGestures(
                            onDoubleTap = { resetMap() },
                            onTap = { tap ->
                                val radius = min(size.width, size.height) * 0.36f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val hit = positions.asReversed().firstOrNull { position ->
                                    val point = center + Offset(position.x, position.y) * radius * scale + Offset(panX, panY)
                                    (point - tap).getDistance() <= hitRadius
                                }
                                onFocusNode(hit?.id)
                            },
                        )
                    }
                    .transformable(transformState),
            ) {
                val center = this.center
                val radius = min(size.width, size.height) * 0.36f
                val camera = Offset(panX, panY)
                repeat(4) { ring ->
                    drawCircle(outline.copy(alpha = 0.16f * reveal), radius = radius * (ring + 1) / 4f * scale, center = center + camera, style = Stroke(1.dp.toPx()))
                }
                edges.forEach { edge ->
                    val from = positionsById[edge.source] ?: return@forEach
                    val to = positionsById[edge.target] ?: return@forEach
                    val start = center + Offset(from.x, from.y) * radius * scale + camera
                    val end = center + Offset(to.x, to.y) * radius * scale + camera
                    drawLine(outline.copy(alpha = 0.24f * reveal), start, end, strokeWidth = 1.dp.toPx())
                }
                positions.forEachIndexed { index, position ->
                    val node = nodes[index]
                    val point = center + Offset(position.x, position.y) * radius * scale + camera
                    val visible = !queryActive || node.id in matchingIds
                    val alpha = (if (visible) 1f else 0.13f) * reveal
                    val nodeRadius = (if (node.id == focusedNodeId) 12.dp else 8.dp).toPx() * min(scale, 1.45f)
                    val color = if (node.kind.equals("skill", true)) primary else secondary
                    if (node.kind.equals("skill", true)) {
                        drawCircle(color.copy(alpha = alpha), nodeRadius, point)
                    } else {
                        val diamond = Path().apply {
                            moveTo(point.x, point.y - nodeRadius)
                            lineTo(point.x + nodeRadius, point.y)
                            lineTo(point.x, point.y + nodeRadius)
                            lineTo(point.x - nodeRadius, point.y)
                            close()
                        }
                        drawPath(diamond, color.copy(alpha = alpha))
                    }
                    if (node.pinned || node.id == focusedNodeId) {
                        drawCircle(
                            (if (node.id == focusedNodeId) selected else outline).copy(alpha = alpha),
                            nodeRadius + 4.dp.toPx(),
                            point,
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                    if (visible && (scale >= 1.45f || node.id == focusedNodeId)) {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = node.label.take(28),
                            topLeft = point + Offset(nodeRadius + 5.dp.toPx(), -7.dp.toPx()),
                            style = TextStyle(color = labelColor.copy(alpha = alpha), fontSize = 10.sp),
                        )
                    }
                }
            }
            Column(
                Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(24.dp)),
            ) {
                IconButton(onClick = { targetScale = (targetScale * 1.35f).coerceAtMost(4f) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                IconButton(onClick = { targetScale = (targetScale / 1.35f).coerceAtLeast(0.55f) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                TextButton(onClick = ::resetMap, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("RESET") }
            }
            Text(
                "● skills   ◆ memory",
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(8.dp)).padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        focusedNode?.let { node ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (node.pinned) Icons.Outlined.Star else Icons.Outlined.Memory, null)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(node.label.take(200), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(node.kind, node.category, node.state).filter(String::isNotBlank).joinToString(" / "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onOpenNode(node.id) }) { Text("Open") }
                }
            }
        }
    }
}

@Composable
private fun SkillList(skills: List<StarmapNode>, edges: List<StarmapEdge>, onOpen: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(skills, key = StarmapNode::id) { node ->
            val connections = edges.count { it.source == node.id || it.target == node.id }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(node.id) },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (node.pinned) Icons.Outlined.Star else Icons.Outlined.Memory, null)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(node.label.take(200), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(node.category, node.state, "${node.useCount} uses", "$connections links")
                                .filter(String::isNotBlank).joinToString(" / "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

internal fun starmapLayout(nodes: List<StarmapNode>): List<StarmapPosition> {
    if (nodes.isEmpty()) return emptyList()
    val timestamps = nodes.mapNotNull(StarmapNode::timestamp)
    val oldest = timestamps.minOrNull()
    val span = ((timestamps.maxOrNull() ?: 0L) - (oldest ?: 0L)).coerceAtLeast(1L)
    return nodes.mapIndexed { index, node ->
        val recency = if (node.timestamp != null && oldest != null) {
            (node.timestamp - oldest).toFloat() / span.toFloat()
        } else {
            index.toFloat() / nodes.size.coerceAtLeast(1)
        }
        val ring = 0.22f + recency * 0.76f
        val angle = ((node.id.hashCode().toLong() and 0xffff_ffffL).toDouble() / 0xffff_ffffL) * 2.0 * PI
        StarmapPosition(node.id, (cos(angle) * ring).toFloat(), (sin(angle) * ring).toFloat())
    }
}

internal fun starmapMatches(node: StarmapNode, query: String): Boolean {
    val term = query.trim()
    return term.isEmpty() || listOf(node.label, node.kind, node.category, node.state, node.createdBy.orEmpty())
        .any { it.contains(term, ignoreCase = true) }
}
