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

import biz.ganttproject.storage.BrowserPaneBuilder
import biz.ganttproject.storage.FolderItem
import biz.ganttproject.storage.FolderView
import biz.ganttproject.storage.StorageDialogBuilder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.google.common.io.CharStreams
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.concurrent.Service
import javafx.concurrent.Task
import javafx.event.EventHandler
import javafx.scene.layout.Pane
import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.language.GanttLanguage
import org.apache.http.client.methods.HttpGet
import org.apache.http.util.EntityUtils
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Paths
import java.util.function.Consumer
import java.util.logging.Level

/**
 * Wraps JSON node matching a team to FolderItem
 */
class TeamJsonAsFolderItem(val node: JsonNode) : FolderItem {
  override val isLocked: Boolean
    get() = false
  override val isLockable: Boolean
    get() = false
  override val name: String
    get() = this.node["name"].asText()
  override val isDirectory: Boolean
    get() = false
}

/**
 * This pane shows the contents of GanttProject Cloud storage
 * for a signed in user.
 *
 * @author dbarashev@bardsoftware.com
 */
class GPCloudBrowserPane(
    private val mode: StorageDialogBuilder.Mode,
    private val dialogUi: StorageDialogBuilder.DialogUi) {
  private val i18n = GanttLanguage.getInstance()
  private lateinit var listView: FolderView<FolderItem>
  private val loaderService = LoaderService(dialogUi)

  fun createStorageUi(): Pane {
    val builder = BrowserPaneBuilder(this.mode, this.dialogUi) { path, success, loading ->
      loadTeams(success, loading)
    }
    val browserPaneElements = builder.apply {
      withBreadcrumbs()
      withActionButton(EventHandler {})
      withListView()
    }.build()

    Platform.runLater {
      browserPaneElements.breadcrumbView.path = Paths.get("/")
    }
    return browserPaneElements.pane
  }

  private fun loadTeams(setResult: Consumer<ObservableList<FolderItem>>, showMaskPane: Consumer<Boolean>) {
    showMaskPane.accept(true)
    loaderService.apply {
      onSucceeded = EventHandler { _ ->
        setResult.accept(value)
        showMaskPane.accept(false)
      }
      onFailed = EventHandler { _ ->
        showMaskPane.accept(false)
        dialogUi.error("Loading failed!")
      }
      onCancelled = EventHandler { _ ->
        showMaskPane.accept(false)
        GPLogger.log("Loading cancelled!")
      }
      restart()
    }
  }
}

// Background tasks which communicate with GP Cloud server and load
// user team and project list.

// We just need to implement a factory of tasjs
class LoaderService(private val dialogUi: StorageDialogBuilder.DialogUi) : Service<ObservableList<FolderItem>>() {
  override fun createTask(): Task<ObservableList<FolderItem>> {
    val task = LoaderTask()
    task.onFailed = EventHandler { _ ->
      val errorDetails = if (task.exception != null) {
        GPLogger.getLogger("GPCloud").log(Level.WARNING, "", task.exception)
        "\n${task.exception.message}"
      } else {
        ""
      }
      this.dialogUi.error("Failed to load data from GanttProject Cloud $errorDetails")
    }
    return task
  }
}

// Implementation of a task which does the real work: sends HTTP request
// and interprets the results.
class LoaderTask : Task<ObservableList<FolderItem>>() {
  override fun call(): ObservableList<FolderItem>? {
    val log = GPLogger.getLogger("GPCloud")
    val http = HttpClientBuilder.buildHttpClient()
    val teamList = HttpGet("/team/list")

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

    val objectMapper = ObjectMapper()
    val jsonNode = objectMapper.readTree(jsonBody)
    return if (jsonNode is ArrayNode) {
      FXCollections.observableArrayList<FolderItem>(
          jsonNode.map(::TeamJsonAsFolderItem))
    } else {
      with(log) {
        fine("Malformed response from GPCloud")
        fine(jsonBody)
      }
      throw IOException("Malformed response: array was expected, got ${jsonNode.javaClass.name}")
    }
  }
}

