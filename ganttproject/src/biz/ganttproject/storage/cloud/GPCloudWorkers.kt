/*
Copyright 2018 BarD Software s.r.o

This file is part of GanttProject, an opensource project management tool.

GanttProject is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

GanttProject is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
*/
package biz.ganttproject.storage.cloud

import biz.ganttproject.storage.FolderItem
import biz.ganttproject.storage.StorageDialogBuilder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.io.CharStreams
import javafx.beans.property.Property
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.concurrent.Service
import javafx.concurrent.Task
import javafx.event.EventHandler
import net.sourceforge.ganttproject.GPLogger
import okhttp3.*
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.message.BasicNameValuePair
import org.apache.http.util.EntityUtils
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.logging.Level

/**
 * @author dbarashev@bardsoftware.com
 */
// Background tasks which communicate with GP Cloud server and load
// user team and project list.

// Create LoadTask or CachedTask depending on whether we have cached response from GP Cloud or not
class LoaderService(private val dialogUi: StorageDialogBuilder.DialogUi) : Service<ObservableList<FolderItem>>() {
  var busyIndicator: Consumer<Boolean> = Consumer {}
  var path: Path = Paths.get("/GanttProject Cloud")
  var jsonResult: SimpleObjectProperty<JsonNode> = SimpleObjectProperty()

  override fun createTask(): Task<ObservableList<FolderItem>> {
    return if (jsonResult.value == null) {
      val task = LoaderTask(busyIndicator, this.path, this.jsonResult)
      task.onFailed = EventHandler { _ ->
        val errorDetails = if (task.exception != null) {
          GPLogger.getLogger("GPCloud").log(Level.WARNING, "", task.exception)
          "\n${task.exception.message}"
        } else {
          ""
        }
        this.dialogUi.error("Failed to load data from GanttProject Cloud $errorDetails")
      }
      task
    } else {
      CachedTask(this.path, this.jsonResult)
    }
  }
}

// Takes the root node of GP Cloud response and filters teams
fun filterTeams(jsonNode: JsonNode, filter: Predicate<JsonNode>): List<JsonNode> {
  return if (jsonNode is ArrayNode) {
    jsonNode.filter(filter::test)
  } else {
    emptyList()
  }
}

// Takes a list of team nodes and returns filtered projects.
// This can work if teams.size > 1 (e.g. to find all projects matching some criteria)
// but in practice we expect teams.size == 1
fun filterProjects(teams: List<JsonNode>, filter: Predicate<JsonNode>): List<JsonNode> {
  return teams.flatMap { team ->
    team.get("projects").let {
      if (it is ArrayNode) {
        it.filter(filter::test).map { project -> project.also { (it as ObjectNode).put("team", team["name"].asText()) } }
      } else {
        emptyList()
      }
    }
  }
}

// Processes cached response from GP Cloud
class CachedTask(val path: Path, private val jsonNode: Property<JsonNode>) : Task<ObservableList<FolderItem>>() {
  override fun call(): ObservableList<FolderItem> {
    return FXCollections.observableArrayList(
        when (path.nameCount) {
          1 -> filterTeams(jsonNode.value, Predicate { true }).map(::TeamJsonAsFolderItem)
          2 -> {
            filterProjects(
                filterTeams(jsonNode.value, Predicate { it["name"].asText() == path.getName(1).toString() }),
                Predicate { true }
            ).map(::ProjectJsonAsFolderItem)
          }
          else -> emptyList()
        })
  }

  fun callPublic(): ObservableList<FolderItem> {
    return this.call()
  }
}

// Sends HTTP request to GP Cloud and returns a list of teams.
class LoaderTask(private val busyIndicator: Consumer<Boolean>,
                 val path: Path,
                 private val resultStorage: Property<JsonNode>) : Task<ObservableList<FolderItem>>() {
  override fun call(): ObservableList<FolderItem>? {
    busyIndicator.accept(true)
    val log = GPLogger.getLogger("GPCloud")
    val http = HttpClientBuilder.buildHttpClient()
    val teamList = HttpGet("/team/list?owned=true&participated=true")

    val jsonBody = let {
      val resp = http.client.execute(http.host, teamList, http.context)
      if (resp.statusLine.statusCode == 200) {
        CharStreams.toString(InputStreamReader(resp.entity.content))
      } else {
        with(log) {
          warning(
              "Failed to get team list. Response code=${resp.statusLine.statusCode} reason=${resp.statusLine.reasonPhrase}")
          fine(EntityUtils.toString(resp.entity))
        }
        throw IOException("Server responded with HTTP ${resp.statusLine.statusCode}")
      }
    }
    println("Team list:\n$jsonBody")

    val objectMapper = ObjectMapper()
    val jsonNode = objectMapper.readTree(jsonBody)
    resultStorage.value = jsonNode
    return CachedTask(this.path, this.resultStorage).callPublic()
  }
}

// Background tasks which communicate with GP Cloud server and acquire or release
// locks on projects.
class LockService(private val dialogUi: StorageDialogBuilder.DialogUi) : Service<Boolean>() {
  var busyIndicator: Consumer<Boolean> = Consumer {}
  lateinit var project: ProjectJsonAsFolderItem

  override fun createTask(): Task<Boolean> {
    val task = LockTask(this.busyIndicator, project)
    task.onFailed = EventHandler { _ ->
      val errorDetails = if (task.exception != null) {
        GPLogger.getLogger("GPCloud").log(Level.WARNING, "", task.exception)
        "\n${task.exception.message}"
      } else {
        ""
      }
      this.dialogUi.error("Failed to lock project: $errorDetails")
    }
    return task
  }
}

// This task toggles project lock status: acquires or releases lock
class LockTask(private val busyIndicator: Consumer<Boolean>,
               val project: ProjectJsonAsFolderItem) : Task<Boolean>() {
  override fun call(): Boolean {
    busyIndicator.accept(true)
    val log = GPLogger.getLogger("GPCloud")
    val http = HttpClientBuilder.buildHttpClient()
    val resp = if (project.isLocked) {
      val projectUnlock = HttpPost("/p/unlock")
      val params = listOf(
          BasicNameValuePair("projectRefid", project.refid))
      projectUnlock.entity = UrlEncodedFormEntity(params)
      http.client.execute(http.host, projectUnlock, http.context)
    } else {
      val projectLock = HttpPost("/p/lock")
      val params = listOf(
          BasicNameValuePair("projectRefid", project.refid),
          BasicNameValuePair("expirationPeriodSeconds", "600"))
      projectLock.entity = UrlEncodedFormEntity(params)

      http.client.execute(http.host, projectLock, http.context)
    }
    if (resp.statusLine.statusCode == 200) {
      return true
    } else {
      with(log) {
        warning(
            "Failed to get lock project. Response code=${resp.statusLine.statusCode} reason=${resp.statusLine.reasonPhrase}")
      }
      throw IOException("Server responded with HTTP ${resp.statusLine.statusCode}")
    }
  }
}

class WebSocketListenerImpl(private val onStructureChange: Consumer<Any>) : WebSocketListener() {
  private var webSocket: WebSocket? = null
  internal var token: String? = null
    set(value) {
      field = value
      trySendToken()
    }

  override fun onOpen(webSocket: WebSocket, response: Response) {
    println("WebSocket opened")
    this.webSocket = webSocket
    this.trySendToken()
  }

  private fun trySendToken() {
    println("Trying sending token ${this.token}")
    if (this.webSocket != null && this.token != null) {
      this.webSocket?.send(this.token!!)
      println("Token is sent!")
    }
  }

  override fun onMessage(webSocket: WebSocket?, text: String?) {
    this.onStructureChange.accept(Any())
  }

  override fun onClosed(webSocket: WebSocket?, code: Int, reason: String?) {
    println("WebSocket closed")
  }
}

class WebSocketClient(private val onStructureChange: Consumer<Any>) {
  private val okClient = OkHttpClient()
  private var isStarted = false
  private val wsListener = WebSocketListenerImpl(onStructureChange)
  var token: String = ""
    set(value) {
      this.wsListener.token = value
    }

  fun start() {
    if (isStarted) {
      return
    }
    val req = Request.Builder().url("wss://ws.ganttproject.biz").build()
    this.okClient.newWebSocket(req, this.wsListener)
    isStarted = true
  }
}
