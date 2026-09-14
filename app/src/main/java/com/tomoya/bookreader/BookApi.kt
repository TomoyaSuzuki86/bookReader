package com.tomoya.bookreader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class BookApi(private val baseUrl: String) {
  private fun connection(path: String, method: String, token: String? = null): HttpURLConnection {
    return (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = 15000
      readTimeout = 60000
      setRequestProperty("Accept", "application/json")
      if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }
  }

  fun login(email: String, password: String): String {
    val c = connection("/api/login", "POST")
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.bufferedWriter().use { it.write(JSONObject().put("email", email).put("password", password).toString()) }
    val body = readBody(c)
    if (c.responseCode !in 200..299) error(JSONObject(body).optString("error", "Login failed"))
    return JSONObject(body).getString("token")
  }

  fun register(email: String, password: String): String {
    val c = connection("/api/register", "POST")
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.bufferedWriter().use { it.write(JSONObject().put("email", email).put("password", password).toString()) }
    val body = readBody(c)
    if (c.responseCode !in 200..299) error(JSONObject(body).optString("error", "Registration failed"))
    return JSONObject(body).getString("token")
  }

  fun books(token: String): List<BookSummary> {
    val c = connection("/api/books", "GET", token)
    val body = readBody(c)
    if (c.responseCode !in 200..299) error("Library request failed (${c.responseCode})")
    val arr = JSONArray(body)
    return (0 until arr.length()).map { i ->
      val o = arr.getJSONObject(i)
      BookSummary(o.getString("id"), o.getString("title"), o.optString("fileName"), o.optInt("lastPage", 0))
    }
  }

  fun download(bookId: String, token: String, target: File) {
    val partial = File(target.parentFile, target.name + ".part")
    partial.delete()
    try {
      val c = connection("/api/books/$bookId/file", "GET", token)
      if (c.responseCode !in 200..299) error("PDF download failed (${c.responseCode})")
      c.inputStream.use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
      if (partial.length() == 0L) error("Downloaded PDF is empty")
      if (target.exists()) target.delete()
      if (!partial.renameTo(target)) {
        partial.copyTo(target, overwrite = true)
        partial.delete()
      }
    } finally {
      if (partial.exists()) partial.delete()
    }
  }

  fun saveProgress(bookId: String, token: String, page: Int) {
    val c = connection("/api/books/$bookId/progress", "PUT", token)
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.bufferedWriter().use { it.write(JSONObject().put("page", page).toString()) }
    val body = readBody(c)
    if (c.responseCode !in 200..299) error(JSONObject(body).optString("error", "Progress update failed"))
  }

  private fun readBody(c: HttpURLConnection): String {
    val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
    return stream?.bufferedReader()?.use { it.readText() } ?: ""
  }
}
