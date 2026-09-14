package com.tomoya.bookreader

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LibraryPanel(controller: ReaderController) {
  val state by controller.state
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  MaterialTheme {
    Surface(Modifier.fillMaxSize(), color = Color(0xEE171717)) {
      Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("BookReader", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Quest 3 PDF Library", color = Color.LightGray)
        if (state.token == null) {
          OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ controller.login(email, password, false) }, enabled = !state.loading) { Text("Login") }
            OutlinedButton({ controller.login(email, password, true) }, enabled = !state.loading) { Text("Create account") }
          }
        } else {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(state.email, color = Color.White)
            TextButton({ controller.refreshBooks() }) { Text("Refresh") }
          }
          LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(state.books) { book ->
              ElevatedButton(onClick = { controller.openBook(book) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) { Text(book.title); Text(book.fileName, style = MaterialTheme.typography.bodySmall) }
              }
            }
          }
          Text("PDFの追加はスマホ/PCのWebアップローダーから行います。", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    }
  }
}

@Composable
fun PagePanel(controller: ReaderController, left: Boolean) {
  val state by controller.state
  var drag by remember { mutableFloatStateOf(0f) }
  val bitmap = if (left) state.leftBitmap else state.rightBitmap
  Box(
    Modifier.fillMaxSize().background(Color.White).pointerInput(state.spreadStart) {
      detectHorizontalDragGestures(
        onDragStart = { drag = 0f },
        onHorizontalDrag = { change, amount -> change.consume(); drag += amount },
        onDragEnd = {
          if (drag > 90f) controller.nextSpread() else if (drag < -90f) controller.previousSpread()
          drag = 0f
        }
      )
    },
    contentAlignment = Alignment.Center
  ) {
    if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    else if (state.isReading) CircularProgressIndicator()
  }
}

@Composable
fun ReaderToolbar(controller: ReaderController) {
  val state by controller.state
  var pageText by remember(state.spreadStart) { mutableStateOf(state.rightPageNumber.takeIf { it > 0 }?.toString() ?: "") }
  MaterialTheme {
    Surface(Modifier.fillMaxSize(), color = Color(0xEE202020)) {
      Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton({ controller.previousSpread() }) { Text("←") }
        Text("${state.rightPageNumber}${if (state.leftPageNumber > 0) "–${state.leftPageNumber}" else ""} / ${state.pageCount}", color = Color.White)
        OutlinedTextField(pageText, { pageText = it.filter(Char::isDigit).take(5) }, label = { Text("Page") }, singleLine = true, modifier = Modifier.width(120.dp))
        Button({ pageText.toIntOrNull()?.let(controller::jumpTo) }) { Text("Jump") }
        TextButton({ controller.nextSpread() }) { Text("→") }
        Spacer(Modifier.weight(1f))
        OutlinedButton({ controller.closeBook() }) { Text("Library") }
      }
    }
  }
}
