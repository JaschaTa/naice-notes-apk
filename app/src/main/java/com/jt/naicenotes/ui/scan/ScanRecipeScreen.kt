package com.jt.naicenotes.ui.scan

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.jt.naicenotes.BuildConfig
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.remote.RecipeScanClient
import com.jt.naicenotes.data.util.ImageProcessor
import com.jt.naicenotes.ui.util.rememberRepository
import kotlinx.coroutines.launch

private sealed interface ScanUiState {
    data object PickingImage : ScanUiState
    data class Scanning(val uri: Uri) : ScanUiState
    data class Reviewing(val items: List<EditableIngredient>) : ScanUiState
    data class Failed(val message: String) : ScanUiState
}

private data class EditableIngredient(val text: String, val include: Boolean = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanRecipeScreen(
    initialSectionId: Long?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repo = rememberRepository()
    val scope = rememberCoroutineScope()
    val sections by repo.observeSections().collectAsStateWithLifecycle(initialValue = emptyList())

    var state by remember { mutableStateOf<ScanUiState>(ScanUiState.PickingImage) }
    var targetId by remember { mutableStateOf(initialSectionId) }

    LaunchedEffect(sections, initialSectionId) {
        if (targetId == null || sections.none { it.id == targetId }) {
            targetId = initialSectionId?.takeIf { id -> sections.any { it.id == id } }
                ?: sections.firstOrNull()?.id
        }
    }

    val client = remember { RecipeScanClient(BuildConfig.RECIPE_SCAN_URL, BuildConfig.RECIPE_SCAN_SECRET) }

    fun runScan(uri: Uri) {
        state = ScanUiState.Scanning(uri)
        scope.launch {
            val bytes = runCatching { ImageProcessor.loadAndDownscale(context, uri) }
            bytes.fold(
                onSuccess = { jpeg ->
                    val result = client.scan(jpeg)
                    result.fold(
                        onSuccess = { ingredients ->
                            state = if (ingredients.isEmpty()) {
                                ScanUiState.Failed("No ingredients detected. Try a clearer photo.")
                            } else {
                                ScanUiState.Reviewing(ingredients.map { EditableIngredient(it) })
                            }
                        },
                        onFailure = { e ->
                            state = ScanUiState.Failed(e.message ?: "Scan failed.")
                        },
                    )
                },
                onFailure = { e ->
                    state = ScanUiState.Failed(e.message ?: "Could not read image.")
                },
            )
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            runScan(uri)
        } else if (state == ScanUiState.PickingImage) {
            onClose()
        }
    }

    LaunchedEffect(Unit) {
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    val accentForTarget = sections.firstOrNull { it.id == targetId }
        ?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(scanTitle(state)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                ScanUiState.PickingImage -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { Text("Opening photo picker…") }
                }
                is ScanUiState.Scanning -> ScanningContent(uri = s.uri, accent = accentForTarget)
                is ScanUiState.Failed -> FailedContent(
                    message = s.message,
                    onRetry = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onCancel = onClose,
                )
                is ScanUiState.Reviewing -> ReviewContent(
                    items = s.items,
                    sections = sections,
                    targetId = targetId,
                    accent = accentForTarget,
                    onItemChange = { idx, newText ->
                        state = s.copy(items = s.items.toMutableList().also {
                            it[idx] = it[idx].copy(text = newText)
                        })
                    },
                    onItemToggleInclude = { idx ->
                        state = s.copy(items = s.items.toMutableList().also {
                            it[idx] = it[idx].copy(include = !it[idx].include)
                        })
                    },
                    onTargetChange = { targetId = it },
                    onAdd = {
                        val target = targetId
                        val toAdd = s.items.filter { it.include && it.text.isNotBlank() }.map { it.text.trim() }
                        if (target != null && toAdd.isNotEmpty()) {
                            scope.launch {
                                repo.bulkAddItems(target, toAdd)
                                onClose()
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun scanTitle(state: ScanUiState): String = when (state) {
    ScanUiState.PickingImage -> "Scan recipe"
    is ScanUiState.Scanning -> "Scanning…"
    is ScanUiState.Reviewing -> "Review ingredients"
    is ScanUiState.Failed -> "Scan failed"
}

@Composable
private fun ScanningContent(uri: Uri, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            ScanSweep()
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Extracting ingredients…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanSweep() {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerY = size.height * progress
        val glowHeight = size.height * 0.22f

        // Wide soft white glow above & below the beam
        val trailBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.35f),
                1f to Color.Transparent,
            ),
            startY = centerY - glowHeight,
            endY = centerY + glowHeight,
        )
        drawRect(
            brush = trailBrush,
            topLeft = Offset(0f, centerY - glowHeight),
            size = Size(size.width, glowHeight * 2),
        )

        // Inner bright halo, tighter
        val innerHalo = size.height * 0.04f
        val innerBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0f),
                0.5f to Color.White.copy(alpha = 0.7f),
                1f to Color.White.copy(alpha = 0f),
            ),
            startY = centerY - innerHalo,
            endY = centerY + innerHalo,
        )
        drawRect(
            brush = innerBrush,
            topLeft = Offset(0f, centerY - innerHalo),
            size = Size(size.width, innerHalo * 2),
        )

        // Crisp bright white scan beam
        val beamThickness = 1.5.dp.toPx()
        drawRect(
            color = Color.White,
            topLeft = Offset(0f, centerY - beamThickness / 2),
            size = Size(size.width, beamThickness),
        )
    }
}

@Composable
private fun FailedContent(message: String, onRetry: () -> Unit, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Couldn't extract ingredients",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = onRetry) { Text("Pick another photo") }
            }
        }
    }
}

@Composable
private fun ReviewContent(
    items: List<EditableIngredient>,
    sections: List<Section>,
    targetId: Long?,
    accent: Color,
    onItemChange: (Int, String) -> Unit,
    onItemToggleInclude: (Int) -> Unit,
    onTargetChange: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val includedCount = items.count { it.include && it.text.isNotBlank() }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Add to",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sections, key = { it.id }) { section ->
                TargetPill(
                    section = section,
                    isActive = section.id == targetId,
                    onClick = { onTargetChange(section.id) },
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            itemsIndexed(items, key = { idx, _ -> idx }) { idx, item ->
                IngredientRow(
                    item = item,
                    accent = accent,
                    onTextChange = { onItemChange(idx, it) },
                    onToggle = { onItemToggleInclude(idx) },
                )
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onAdd,
                enabled = includedCount > 0 && targetId != null,
            ) {
                Text(
                    text = if (includedCount > 0) {
                        "Add $includedCount ${if (includedCount == 1) "item" else "items"}"
                    } else "Add",
                )
            }
        }
    }
}

@Composable
private fun TargetPill(
    section: Section,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val accent = Color(section.color)
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val bg = if (isActive) accent else Color.Transparent
    val fg = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface
    val border = if (isActive) accent else outline
    val dotColor = if (isActive) Color.White else accent

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(bg, CircleShape)
            .border(1.5.dp, border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = section.name,
            color = fg,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun IngredientRow(
    item: EditableIngredient,
    accent: Color,
    onTextChange: (String) -> Unit,
    onToggle: () -> Unit,
) {
    val textColor = if (item.include) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant
    val decoration = if (item.include) TextDecoration.None else TextDecoration.LineThrough
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = textColor,
        textDecoration = decoration,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (item.include) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (item.include) "Included" else "Excluded",
                tint = if (item.include) accent else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
        }
        BasicTextField(
            value = item.text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(accent),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 32.dp)
                .padding(vertical = 6.dp),
        )
    }
}
