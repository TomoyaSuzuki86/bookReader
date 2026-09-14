package com.tomoya.bookreader

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun LibraryPanel(controller: ReaderController) {
  val state by controller.state
  var serverUrl by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
  var email by remember(state.email) { mutableStateOf(state.email) }
  var password by remember { mutableStateOf("") }

  MaterialTheme {
    Surface(Modifier.fillMaxSize(), color = Color(0xF2171717)) {
      Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("BookReader", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Text("PDFをスマホ/PCから送って、Questでは本を選ぶだけ。", color = Color.LightGray)

        if (state.token == null) {
          OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ controller.login(email, password, serverUrl, false) }, enabled = !state.loading) { Text("Login") }
            OutlinedButton({ controller.login(email, password, serverUrl, true) }, enabled = !state.loading) { Text("Create account") }
          }
          Text("本番利用はHTTPSのサーバーURLを指定してください。", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        } else {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(state.email, color = Color.White)
              Text(state.serverUrl, color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
              Text(if (state.librarySyncing) "同期中…" else "自動同期 ON", color = if (state.librarySyncing) Color(0xFF80CBC4) else Color(0xFFA5D6A7), style = MaterialTheme.typography.bodySmall)
              Row {
                TextButton({ controller.refreshBooks() }) { Text("Sync now") }
                TextButton({ controller.logout() }) { Text("Logout") }
              }
            }
          }

          if (state.books.isEmpty() && !state.loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
              Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("まだPDFがありません", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("スマホ/PCで同じServer URLを開き、PDFをアップロードしてください。\n数秒でこの本棚に自動表示されます。", color = Color.LightGray)
              }
            }
          } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
              items(state.books, key = { it.id }) { book ->
                ElevatedButton(onClick = { controller.openBook(book) }, modifier = Modifier.fillMaxWidth()) {
                  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                      Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                      Text(book.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                      if (book.id in state.cachedBookIds) "端末保存済み" else "クラウド",
                      style = MaterialTheme.typography.labelSmall,
                      color = if (book.id in state.cachedBookIds) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                    )
                  }
                }
              }
            }
          }

          Text("スマホ/PC側でアップロード後、Quest側は手動更新不要です。", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }

        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    }
  }
}

@Composable
fun BookBackPanel() {
  Box(Modifier.fillMaxSize().background(Color(0xFF2B211C))) {
    Box(
      Modifier
        .width(14.dp)
        .fillMaxHeight()
        .align(Alignment.Center)
        .background(Color(0xFF17110E))
    )
  }
}

@Composable
fun BookSpreadPanel(controller: ReaderController) {
  val state by controller.state
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val cameraDistancePx = with(density) { 54.dp.toPx() }

  var widthPx by remember { mutableFloatStateOf(1f) }
  var dragPx by remember { mutableFloatStateOf(0f) }
  var direction by remember { mutableIntStateOf(0) } // +1 next, -1 previous
  var turnProgress by remember { mutableFloatStateOf(0f) }
  var settling by remember { mutableStateOf(false) }
  var awaitingPageChange by remember { mutableStateOf(false) }

  LaunchedEffect(state.spreadStart) {
    if (awaitingPageChange) {
      dragPx = 0f
      direction = 0
      turnProgress = 0f
      settling = false
      awaitingPageChange = false
    }
  }

  fun settle(commit: Boolean) {
    if (settling) return
    val dir = direction
    settling = true
    scope.launch {
      val anim = Animatable(turnProgress)
      anim.animateTo(if (commit) 1f else 0f, tween(if (commit) 240 else 150)) {
        turnProgress = value
      }
      if (!commit) {
        dragPx = 0f
        direction = 0
        settling = false
        return@launch
      }
      awaitingPageChange = true
      if (dir > 0) controller.nextSpread() else controller.previousSpread()
      delay(1800)
      if (awaitingPageChange) {
        dragPx = 0f
        direction = 0
        turnProgress = 0f
        settling = false
        awaitingPageChange = false
      }
    }
  }

  BoxWithConstraints(
    Modifier
      .fillMaxSize()
      .onSizeChanged { widthPx = it.width.coerceAtLeast(1).toFloat() }
      .pointerInput(state.spreadStart, state.pageCount, settling) {
        detectHorizontalDragGestures(
          onDragStart = {
            if (!settling) {
              dragPx = 0f
              direction = 0
              turnProgress = 0f
            }
          },
          onHorizontalDrag = { change, amount ->
            change.consume()
            if (!settling) {
              dragPx += amount
              direction = if (dragPx >= 0f) 1 else -1
              val allowed = if (direction > 0) state.canGoNext else state.canGoPrevious
              val raw = (abs(dragPx) / (widthPx * 0.52f)).coerceIn(0f, 0.98f)
              turnProgress = if (allowed) raw else raw * 0.10f
            }
          },
          onDragEnd = {
            val allowed = if (direction > 0) state.canGoNext else if (direction < 0) state.canGoPrevious else false
            settle(allowed && turnProgress >= 0.22f)
          },
          onDragCancel = { settle(false) },
        )
      },
    contentAlignment = Alignment.Center,
  ) {
    val halfWidth = maxWidth / 2
    val turningNext = direction > 0 && turnProgress > 0f
    val turningPrevious = direction < 0 && turnProgress > 0f

    val visibleLeft = if (turningNext) state.nextLeftBitmap ?: state.leftBitmap else state.leftBitmap
    val visibleRight = if (turningPrevious) state.previousRightBitmap ?: state.rightBitmap else state.rightBitmap
    val leftNumber = if (turningNext && state.nextLeftBitmap != null) state.spreadStart + 4 else state.leftPageNumber
    val rightNumber = if (turningPrevious && state.previousRightBitmap != null) state.spreadStart - 1 else state.rightPageNumber

    StaticPage(
      bitmap = visibleLeft,
      pageNumber = leftNumber,
      modifier = Modifier.width(halfWidth).fillMaxHeight().align(Alignment.CenterStart),
    )
    StaticPage(
      bitmap = visibleRight,
      pageNumber = rightNumber,
      modifier = Modifier.width(halfWidth).fillMaxHeight().align(Alignment.CenterEnd),
    )

    Box(
      Modifier
        .width(10.dp)
        .fillMaxHeight()
        .align(Alignment.Center)
        .background(
          Brush.horizontalGradient(
            listOf(Color.Black.copy(alpha = 0.17f), Color.Transparent, Color.Black.copy(alpha = 0.14f))
          )
        )
    )

    if (turningNext) {
      CurlingPage(
        front = state.leftBitmap,
        back = state.nextRightBitmap,
        progress = turnProgress,
        next = true,
        cameraDistancePx = cameraDistancePx,
        modifier = Modifier.width(halfWidth).fillMaxHeight().align(Alignment.CenterStart),
      )
    } else if (turningPrevious) {
      CurlingPage(
        front = state.rightBitmap,
        back = state.previousLeftBitmap,
        progress = turnProgress,
        next = false,
        cameraDistancePx = cameraDistancePx,
        modifier = Modifier.width(halfWidth).fillMaxHeight().align(Alignment.CenterEnd),
      )
    }
  }
}

@Composable
private fun StaticPage(bitmap: Bitmap?, pageNumber: Int, modifier: Modifier = Modifier) {
  Box(modifier.background(Color(0xFFFEFDF9)), contentAlignment = Alignment.Center) {
    if (bitmap != null) {
      Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
    if (pageNumber > 0) {
      Text(
        pageNumber.toString(),
        color = Color(0xFF77736D),
        fontSize = 10.sp,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp),
      )
    }
  }
}

@Composable
private fun CurlingPage(
  front: Bitmap?,
  back: Bitmap?,
  progress: Float,
  next: Boolean,
  cameraDistancePx: Float,
  modifier: Modifier = Modifier,
) {
  val showBack = progress > 0.5f
  val pageBitmap = if (showBack) back else front
  val globalAngle = (if (next) -180f else 180f) * progress
  val wave = sin(Math.PI * progress.toDouble()).toFloat().coerceAtLeast(0f)
  val hinge = if (next) TransformOrigin(1f, 0.5f) else TransformOrigin(0f, 0.5f)

  Box(
    modifier.graphicsLayer {
      transformOrigin = hinge
      rotationY = globalAngle
      cameraDistance = cameraDistancePx
      shadowElevation = 12f * wave
      clip = false
    }
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .graphicsLayer {
          if (showBack) rotationY = 180f
          cameraDistance = cameraDistancePx
          clip = false
        }
    ) {
      SegmentedPaper(
        bitmap = pageBitmap,
        progress = progress,
        next = next,
        cameraDistancePx = cameraDistancePx,
      )

      val foldAlpha = 0.08f + 0.30f * wave
      Box(
        Modifier
          .fillMaxHeight()
          .width((18f + 34f * wave).dp)
          .align(if (next) Alignment.CenterEnd else Alignment.CenterStart)
          .background(
            if (next) {
              Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = foldAlpha)))
            } else {
              Brush.horizontalGradient(listOf(Color.Black.copy(alpha = foldAlpha), Color.Transparent))
            }
          )
      )
    }
  }
}

@Composable
private fun SegmentedPaper(
  bitmap: Bitmap?,
  progress: Float,
  next: Boolean,
  cameraDistancePx: Float,
) {
  val segmentCount = 24
  val wave = sin(Math.PI * progress.toDouble()).toFloat().coerceAtLeast(0f)
  Row(Modifier.fillMaxSize()) {
    repeat(segmentCount) { index ->
      val fromHinge = if (next) {
        (segmentCount - 1 - index).toFloat() / (segmentCount - 1).toFloat()
      } else {
        index.toFloat() / (segmentCount - 1).toFloat()
      }
      val localAngle = (if (next) -1f else 1f) * 38f * wave * fromHinge * fromHinge
      Box(
        Modifier
          .weight(1f)
          .fillMaxHeight()
          .graphicsLayer {
            transformOrigin = if (next) TransformOrigin(1f, 0.5f) else TransformOrigin(0f, 0.5f)
            rotationY = localAngle
            cameraDistance = cameraDistancePx
            scaleX = 1.025f
            shadowElevation = 5f * wave * fromHinge
            clip = true
          }
          .background(Color(0xFFFEFDF9))
      ) {
        PageStrip(bitmap = bitmap, index = index, count = segmentCount)
      }
    }
  }
}

@Composable
private fun PageStrip(bitmap: Bitmap?, index: Int, count: Int) {
  if (bitmap == null) return
  val image = remember(bitmap) { bitmap.asImageBitmap() }
  Canvas(Modifier.fillMaxSize()) {
    val fullWidth = size.width * count
    val scale = min(fullWidth / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
    val dstWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val dstHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    val imageLeft = ((fullWidth - dstWidth) / 2f).roundToInt()
    val imageTop = ((size.height - dstHeight) / 2f).roundToInt()
    val stripGlobalX = (index * size.width).roundToInt()
    drawImage(
      image = image,
      dstOffset = IntOffset(imageLeft - stripGlobalX, imageTop),
      dstSize = IntSize(dstWidth, dstHeight),
    )
  }
}

@Composable
fun ReaderToolbar(controller: ReaderController) {
  val state by controller.state
  var pageText by remember(state.spreadStart) { mutableStateOf(state.rightPageNumber.takeIf { it > 0 }?.toString() ?: "") }
  val progress = if (state.pageCount > 0) ((state.spreadStart + 1).toFloat() / state.pageCount).coerceIn(0f, 1f) else 0f

  MaterialTheme {
    Surface(Modifier.fillMaxSize(), color = Color(0xEE202020)) {
      Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          TextButton({ controller.previousSpread() }, enabled = state.canGoPrevious) { Text("←") }
          Column(Modifier.widthIn(min = 110.dp)) {
            Text(state.openBook?.title ?: "", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Text("${state.rightPageNumber}${if (state.leftPageNumber > 0) "–${state.leftPageNumber}" else ""} / ${state.pageCount}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
          }
          OutlinedTextField(pageText, { pageText = it.filter(Char::isDigit).take(5) }, label = { Text("Page") }, singleLine = true, modifier = Modifier.width(112.dp))
          Button({ pageText.toIntOrNull()?.let(controller::jumpTo) }) { Text("Jump") }
          TextButton({ controller.nextSpread() }, enabled = state.canGoNext) { Text("→") }
          Spacer(Modifier.weight(1f))
          OutlinedButton({ controller.closeBook() }) { Text("Library") }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
      }
    }
  }
}
