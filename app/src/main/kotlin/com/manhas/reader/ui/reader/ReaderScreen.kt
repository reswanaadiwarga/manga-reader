package com.manhas.reader.ui.reader

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.manhas.reader.Reader
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
  args: Reader,
  onBack: () -> Unit,
) {
  val app = LocalContext.current.applicationContext as Application
  val viewModel: ReaderViewModel = viewModel { ReaderViewModel(app) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val chromeScope = rememberCoroutineScope()
  val chrome = remember { ChromeController(chromeScope) }
  LaunchedEffect(Unit) {
    viewModel.start(
      documentUri = args.documentUri,
      documentId = args.documentId,
      folderTreeUri = args.folderTreeUri,
      folderDocumentId = args.folderDocumentId,
      title = args.title,
    )
  }

  val view = LocalView.current
  val context = LocalContext.current
  val darkTheme = isSystemInDarkTheme()
  val insetsController = remember(context, view) {
    (context as? Activity)?.let { WindowCompat.getInsetsController(it.window, view) }
  }
  DisposableEffect(insetsController, darkTheme) {
    if (insetsController == null) {
      onDispose { }
    } else {
      insetsController.isAppearanceLightStatusBars = false
      insetsController.isAppearanceLightNavigationBars = false
      onDispose {
        insetsController.isAppearanceLightStatusBars = !darkTheme
        insetsController.isAppearanceLightNavigationBars = !darkTheme
      }
    }
  }
  LaunchedEffect(insetsController, chrome.visible) {
    insetsController?.isAppearanceLightStatusBars = !chrome.visible
  }
  DisposableEffect(chrome) {
    onDispose { chrome.dispose() }
  }

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black),
  ) {
    val density = LocalDensity.current
    val widthDp = maxWidth
    val widthPx = with(density) { maxWidth.toPx().toInt() }
    val listState = rememberLazyListState()
    val pageCount = state.pageRatios.size
    val currentPage by remember(pageCount) {
      derivedStateOf {
        if (pageCount == 0) 0 else (listState.firstVisibleItemIndex + 1).coerceAtMost(pageCount)
      }
    }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pageOffsets = remember(state.pageRatios, widthPx) {
      val offsets = IntArray(state.pageRatios.size + 1)
      state.pageRatios.forEachIndexed { index, ratio ->
        offsets[index + 1] = offsets[index] + (widthPx * ratio).roundToInt()
      }
      offsets
    }
    val viewportHeightPx = with(density) { maxHeight.toPx() }
    val contentHeightPx = with(density) {
      pageOffsets.last().toFloat() +
        (if (state.nextTitle != null) 280.dp else 80.dp).toPx() +
        bottomInset.toPx()
    }

    val zoom = remember { ZoomState() }
    zoom.viewportWidth = widthPx.toFloat()
    zoom.viewportHeight = viewportHeightPx
    var renderScale by remember { mutableFloatStateOf(1f) }
    val zoomed = zoom.scale > 1.001f
    val renderWidthPx = (widthPx * renderScale).roundToInt().coerceIn(1, MAX_RENDER_WIDTH)

    LaunchedEffect(state.documentToken) {
      zoom.reset()
      renderScale = 1f
      if (state.pageRatios.isNotEmpty()) {
        chrome.scheduleHide()
        val page = state.initialPage.coerceIn(0, state.pageRatios.lastIndex)
        listState.scrollToItem(page)
        viewModel.onPageVisible(page)
      }
    }
    LaunchedEffect(listState, chrome) {
      snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
        if (scrolling) {
          chrome.showWhileScrolling()
        } else {
          chrome.scheduleHide()
        }
      }
    }
    LaunchedEffect(zoom.scale) {
      if (zoom.scale <= 1.001f) {
        renderScale = 1f
      } else {
        delay(250)
        renderScale = zoom.scale.coerceAtMost(2f)
      }
    }
    LaunchedEffect(listState, state.documentToken) {
      snapshotFlow { listState.firstVisibleItemIndex }
        .distinctUntilChanged()
        .collectLatest { page ->
          viewModel.onPageVisible(page)
          delay(700)
          viewModel.saveProgressNow()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
      val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
          viewModel.saveProgressNow()
        }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose {
        lifecycleOwner.lifecycle.removeObserver(observer)
        viewModel.saveProgressNow()
      }
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .zoomGestures(zoom, chrome, widthPx, viewportHeightPx),
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer {
            scaleX = zoom.scale
            scaleY = zoom.scale
            translationX = zoom.offset.x
            translationY = zoom.offset.y
            transformOrigin = TransformOrigin(0f, 0f)
          },
        userScrollEnabled = !zoomed,
        contentPadding = PaddingValues(bottom = bottomInset + 16.dp),
      ) {
        itemsIndexed(state.pageRatios) { index, ratio ->
          ReaderPage(
            index = index,
            ratio = ratio,
            widthDp = widthDp,
            renderWidthPx = renderWidthPx,
            documentToken = state.documentToken,
            render = viewModel::renderPage,
          )
        }
        val previousTitle = state.previousTitle
        val nextTitle = state.nextTitle
        if (previousTitle != null || nextTitle != null) {
          item(key = "chapter-footer") {
            ChapterFooter(
              previousTitle = previousTitle,
              nextTitle = nextTitle,
              onPrevious = viewModel::openPrevious,
              onNext = viewModel::openNext,
            )
          }
        }
        if (state.pageRatios.isNotEmpty()) {
          item(key = "end-of-document") {
            Text(
              text = "End of document",
              color = Color(0xFF777777),
              style = MaterialTheme.typography.bodySmall,
              textAlign = TextAlign.Center,
              modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            )
          }
        }
      }

      if (pageCount > 0 && !zoomed) {
        ReaderScrollbar(
          listState = listState,
          viewportHeightPx = viewportHeightPx,
          contentHeightPx = contentHeightPx,
          pageOffsets = pageOffsets,
          bottomInset = bottomInset,
          modifier = Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .width(24.dp),
        )
      }
    }

    AnimatedVisibility(
      visible = chrome.visible,
      modifier = Modifier.align(Alignment.TopCenter),
      enter = slideInVertically(animationSpec = tween(240), initialOffsetY = { -it }) +
        fadeIn(animationSpec = tween(200)),
      exit = slideOutVertically(animationSpec = tween(240), targetOffsetY = { -it }) +
        fadeOut(animationSpec = tween(200)),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xE6000000))
          .statusBarsPadding()
          .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = state.title,
          color = Color.White,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        if (pageCount > 0) {
          Spacer(modifier = Modifier.width(12.dp))
          Text(
            text = "$currentPage / $pageCount",
            color = Color(0xFFBDBDBD),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }

    if (state.loading) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(color = Color.White)
      }
    }

    val error = state.error
    if (error != null) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
          modifier = Modifier.padding(24.dp),
        ) {
          Text(
            text = "Cannot open document",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = error,
            color = Color(0xFFBDBDBD),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(16.dp))
          Button(onClick = onBack) {
            Text("Back")
          }
        }
      }
    }
  }
}

@Composable
private fun ReaderScrollbar(
  listState: LazyListState,
  viewportHeightPx: Float,
  contentHeightPx: Float,
  pageOffsets: IntArray,
  bottomInset: Dp,
  modifier: Modifier = Modifier,
) {
  val pageCount = pageOffsets.size - 1
  val viewportHeight = max(viewportHeightPx, 1f)
  val contentHeight = max(contentHeightPx, 1f)
  if (pageCount <= 0 || contentHeight <= viewportHeight + 1f) return

  val density = LocalDensity.current
  val trackTopPx = with(density) { 76.dp.toPx() }
  val trackBottomPx = with(density) { bottomInset.toPx() + 16.dp.toPx() }
  val trackHeight = (viewportHeight - trackTopPx - trackBottomPx).coerceAtLeast(1f)
  val scrollRange = (contentHeight - viewportHeight).coerceAtLeast(1f)
  val minThumb = with(density) { 36.dp.toPx() }
  val thumbHeight = (trackHeight * (viewportHeight / contentHeight))
    .coerceIn(min(minThumb, trackHeight), trackHeight)
  val thumbTravel = (trackHeight - thumbHeight).coerceAtLeast(1f)

  val scope = rememberCoroutineScope()
  var dragging by remember { mutableStateOf(false) }
  var recentlyScrolled by remember { mutableStateOf(false) }
  LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
      if (scrolling) {
        recentlyScrolled = true
      } else {
        delay(1200)
        recentlyScrolled = false
      }
    }
  }
  val alpha by animateFloatAsState(
    targetValue = if (dragging || recentlyScrolled) 0.75f else 0f,
    animationSpec = tween(180),
    label = "scrollbarAlpha",
  )

  val scrollFraction by remember(scrollRange, pageCount, pageOffsets) {
    derivedStateOf {
      val index = listState.firstVisibleItemIndex
      val base = if (index < pageCount) pageOffsets[index] else pageOffsets[pageCount]
      val offset = base + if (index < pageCount) listState.firstVisibleItemScrollOffset else 0
      (offset.toFloat() / scrollRange).coerceIn(0f, 1f)
    }
  }
  val thumbTop = scrollFraction * thumbTravel

  fun seekTo(fraction: Float) {
    val target = fraction.coerceIn(0f, 1f) * scrollRange
    var page = 0
    while (page < pageCount - 1 && pageOffsets[page + 1] <= target) page++
    val withinPage = (target - pageOffsets[page]).roundToInt().coerceAtLeast(0)
    scope.launch { listState.scrollToItem(page, withinPage) }
  }

  Box(
    modifier = modifier.pointerInput(
      pageCount,
      pageOffsets,
      viewportHeight,
      contentHeight,
      trackTopPx,
      thumbHeight,
      thumbTravel,
    ) {
      fun fractionFor(y: Float): Float =
        ((y - trackTopPx - thumbHeight / 2f) / thumbTravel).coerceIn(0f, 1f)

      detectVerticalDragGestures(
        onDragStart = { offset ->
          dragging = true
          seekTo(fractionFor(offset.y))
        },
        onDragEnd = { dragging = false },
        onDragCancel = { dragging = false },
        onVerticalDrag = { change, _ ->
          change.consume()
          seekTo(fractionFor(change.position.y))
        },
      )
    },
    contentAlignment = Alignment.TopEnd,
  ) {
    Box(
      modifier = Modifier
        .offset(y = with(density) { (trackTopPx + thumbTop).toDp() })
        .width(6.dp)
        .height(with(density) { thumbHeight.toDp() })
        .clip(RoundedCornerShape(3.dp))
        .background(Color.White)
        .graphicsLayer { this.alpha = alpha },
    )
  }
}

@Composable
private fun ReaderPage(
  index: Int,
  ratio: Float,
  widthDp: Dp,
  renderWidthPx: Int,
  documentToken: Int,
  render: suspend (Int, Int) -> Bitmap?,
) {
  var bitmap by remember(index, documentToken) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(index, renderWidthPx, documentToken) {
    val next = render(index, renderWidthPx)
    if (next != null) {
      bitmap = next
    }
  }
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(widthDp * ratio)
      .background(Color.White),
    contentAlignment = Alignment.Center,
  ) {
    val rendered = bitmap
    if (rendered != null) {
      Image(
        bitmap = rendered.asImageBitmap(),
        contentDescription = "Page ${index + 1}",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.FillWidth,
      )
    } else {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFF1B1B1B)),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "${index + 1}",
          color = Color(0xFF555555),
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
private fun ChapterFooter(
  previousTitle: String?,
  nextTitle: String?,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
) {
  Surface(
    color = Color(0xFF161616),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 24.dp),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (previousTitle != null) {
        ChapterAction(
          label = "Previous chapter",
          title = previousTitle,
          onOpen = onPrevious,
          primary = false,
          modifier = Modifier.weight(1f),
        )
      }
      if (nextTitle != null) {
        ChapterAction(
          label = "Next chapter",
          title = nextTitle,
          onOpen = onNext,
          primary = true,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun ChapterAction(
  label: String,
  title: String,
  onOpen: () -> Unit,
  primary: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = label,
      color = Color(0xFF9E9E9E),
      style = MaterialTheme.typography.labelMedium,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = title,
      color = Color.White,
      style = MaterialTheme.typography.titleSmall,
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.heightIn(min = 42.dp),
    )
    Spacer(modifier = Modifier.height(12.dp))
    if (primary) {
      Button(onClick = onOpen) {
        Text(label)
      }
    } else {
      OutlinedButton(onClick = onOpen) {
        Text(label)
      }
    }
  }
}

private const val MAX_RENDER_WIDTH = 2048

private class ChromeController(private val scope: CoroutineScope) {
  var visible by mutableStateOf(true)
    private set
  private var hideJob: Job? = null

  fun showWhileScrolling() {
    hideJob?.cancel()
    visible = true
  }

  fun scheduleHide() {
    hideJob?.cancel()
    visible = true
    hideJob = scope.launch {
      delay(2500)
      visible = false
    }
  }

  fun toggle() {
    if (visible) {
      hideJob?.cancel()
      visible = false
    } else {
      scheduleHide()
    }
  }

  fun dispose() {
    hideJob?.cancel()
  }
}

private class ZoomState {
  var scale by mutableFloatStateOf(1f)
    private set
  var offset by mutableStateOf(Offset.Zero)
    private set
  var viewportWidth: Float = 1f
  var viewportHeight: Float = 1f

  fun reset() {
    scale = 1f
    offset = Offset.Zero
  }

  fun zoomBy(factor: Float, focal: Offset) {
    val target = (scale * factor).coerceIn(1f, 4f)
    if (target <= 1.001f) {
      reset()
      return
    }
    val ratio = target / scale
    val raw = Offset(
      focal.x - (focal.x - offset.x) * ratio,
      focal.y - (focal.y - offset.y) * ratio,
    )
    scale = target
    offset = clamp(raw, target)
  }

  fun pan(delta: Offset) {
    if (scale <= 1.001f) return
    offset = clamp(offset + delta, scale)
  }

  fun doubleTap(focal: Offset) {
    if (scale > 1.001f) {
      reset()
    } else {
      zoomBy(2f, focal)
    }
  }

  private fun clamp(value: Offset, currentScale: Float): Offset {
    val minX = -(currentScale - 1f) * viewportWidth
    val minY = -(currentScale - 1f) * viewportHeight
    return Offset(
      value.x.coerceIn(minX, 0f),
      value.y.coerceIn(minY, 0f),
    )
  }
}

private fun Modifier.zoomGestures(
  zoom: ZoomState,
  chrome: ChromeController,
  viewportWidthPx: Int,
  viewportHeightPx: Float,
): Modifier = this
  .pointerInput(zoom, viewportWidthPx, viewportHeightPx) {
    awaitEachGesture {
      var handling = zoom.scale > 1.001f
      var lastCentroid = Offset.Zero
      var hasCentroid = false
      var lastDistance = 0f
      awaitFirstDown(requireUnconsumed = false)
      do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size >= 2) handling = true
        if (handling && pressed.isNotEmpty()) {
          val centroid = pressed.fold(Offset.Zero) { acc, change -> acc + change.position } /
            pressed.size.toFloat()
          if (pressed.size >= 2) {
            val distance = (pressed[0].position - pressed[1].position).getDistance()
            if (hasCentroid && lastDistance > 0f && distance > 0f) {
              val factor = distance / lastDistance
              if (abs(factor - 1f) > 0.001f) {
                zoom.zoomBy(factor, centroid)
                event.changes.forEach { it.consume() }
              }
            }
            lastDistance = distance
          } else if (hasCentroid) {
            val delta = centroid - lastCentroid
            if (delta.getDistance() > 0.5f) {
              zoom.pan(delta)
              event.changes.forEach { it.consume() }
            }
          }
          lastCentroid = centroid
          hasCentroid = true
        }
      } while (event.changes.any { it.pressed })
    }
  }
  .pointerInput(zoom, chrome) {
    detectTapGestures(
      onTap = { chrome.toggle() },
      onDoubleTap = { position -> zoom.doubleTap(position) },
    )
  }
