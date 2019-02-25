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

import biz.ganttproject.app.DefaultLocalizer
import biz.ganttproject.app.OptionElementData
import biz.ganttproject.app.OptionPaneBuilder
import biz.ganttproject.core.time.CalendarFactory
import biz.ganttproject.storage.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView
import javafx.application.Platform
import javafx.collections.ObservableList
import javafx.event.EventHandler
import javafx.scene.control.CheckBox
import javafx.scene.layout.Pane
import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.document.DocumentManager
import net.sourceforge.ganttproject.language.GanttLanguage
import org.controlsfx.control.Notifications
import java.time.Instant
import java.util.function.Consumer
import java.util.logging.Level

/**
 * Wraps JSON node matching a team to FolderItem
 */
class TeamJsonAsFolderItem(val node: JsonNode) : FolderItem {
  override val isLocked = false
  override val isLockable = false
  override val canChangeLock = false
  override val name: String
    get() = this.node["name"].asText()
  override val isDirectory = true
}

class ProjectJsonAsFolderItem(val node: JsonNode) : FolderItem {
  override val canChangeLock: Boolean
    get() {
      return if (!isLocked) isLockable else {
        val lockNode = this.node["lock"]
        lockNode["uid"].textValue() == GPCloudOptions.userId.value
      }
    }
  override val isLocked: Boolean
    get() {
      val lockNode = this.node["lock"]
      return if (lockNode is ObjectNode) {
        lockNode["expirationEpochTs"].asLong(0) > Instant.now().toEpochMilli()
      } else {
        false
      }
    }
  val lockOwner: String?
    get() {
      val lockNode = this.node["lock"]
      return if (lockNode is ObjectNode) {
        lockNode["name"]?.textValue()
      } else {
        null
      }
    }
  val lockOwnerEmail: String?
    get() {
      val lockNode = this.node["lock"]
      return if (lockNode is ObjectNode) {
        lockNode["email"]?.textValue()
      } else {
        null
      }
    }
  val lockOwnerId: String?
    get() {
      val lockNode = this.node["lock"]
      return if (lockNode is ObjectNode) {
        lockNode["uid"]?.textValue()
      } else {
        null
      }
    }

  override val isLockable = true
  override val name: String
    get() = this.node["name"].asText()
  override val isDirectory = false
  val refid: String = this.node["refid"].asText()

}

class VersionJsonAsFolderItem(val node: JsonNode) : FolderItem {
  override val isLocked = false
  override val isLockable = false
  override val name: String
    get() = node["author"].toString().removeSurrounding("\"")
  override val isDirectory = false
  override val canChangeLock = false

  fun formatTimestamp(): String {
    return GanttLanguage.getInstance().formatDateTime(CalendarFactory.newCalendar().let {
      it.timeInMillis = node["timestamp"].asLong()
      it.time
    })
  }
}

/**
 * This pane shows the contents of GanttProject Cloud storage
 * for a signed in user.
 *
 * @author dbarashev@bardsoftware.com
 */
class GPCloudBrowserPane(
    private val mode: StorageDialogBuilder.Mode,
    private val dialogUi: StorageDialogBuilder.DialogUi,
    private val documentConsumer: Consumer<Document>,
    private val documentManager: DocumentManager,
    private val sceneChanger: SceneChanger) {
  private val loaderService = LoaderService(dialogUi)

  private lateinit var paneElements: BrowserPaneElements

  fun createStorageUi(): Pane {
    val builder = BrowserPaneBuilder(this.mode, this.dialogUi) { path, success, loading ->
      loadTeams(path, success, loading)
    }

    val actionButtonHandler = object {
      var selectedProject: ProjectJsonAsFolderItem? = null
      var selectedTeam: TeamJsonAsFolderItem? = null

      fun onOpenItem(item: FolderItem) {
        when (item) {
          is ProjectJsonAsFolderItem -> selectedProject = item
          is TeamJsonAsFolderItem -> selectedTeam = item
          else -> {
          }
        }

      }

      fun onAction() {
        selectedProject?.let { this@GPCloudBrowserPane.openDocument(it) }
            ?: this@GPCloudBrowserPane.createDocument(selectedTeam, paneElements.filenameInput.text)

      }
    }

    this.paneElements = builder.apply {
      withI18N(DefaultLocalizer("storageService.cloud", BROWSE_PANE_LOCALIZER))
      withBreadcrumbs(DocumentUri(listOf(), true, "GanttProject Cloud"))
      withActionButton(EventHandler { actionButtonHandler.onAction() })
      withListView(
          onOpenItem = Consumer { actionButtonHandler.onOpenItem(it) },
          onLaunch = Consumer {
            if (it is ProjectJsonAsFolderItem) {
              this@GPCloudBrowserPane.openDocument(it)
            }
          }/*
          onLock = Consumer {
            if (it is ProjectJsonAsFolderItem) {
              this@GPCloudBrowserPane.toggleProjectLock(it,
                  Consumer { this@GPCloudBrowserPane.reload() },
                  builder.busyIndicatorToggler
              )
            }
          },*/
      )
    }.build()
    paneElements.browserPane.stylesheets.add("/biz/ganttproject/storage/cloud/GPCloudStorage.css")

    webSocket.onStructureChange { Platform.runLater { this.reload() } }
    return paneElements.browserPane
  }

  private fun createDocument(selectedTeam: TeamJsonAsFolderItem?, text: String) {
    if (selectedTeam == null) {
      return
    }
    this.documentConsumer.accept(GPCloudDocument(selectedTeam, text).also {
      it.offlineDocumentFactory = { path -> this.documentManager.newDocument(path) }
      it.proxyDocumentFactory = this.documentManager::getProxyDocument
    })
  }

  private fun openDocument(item: ProjectJsonAsFolderItem) {
    if (item.node is ObjectNode) {
      val document = GPCloudDocument(item)
      document.offlineDocumentFactory = { path -> this.documentManager.newDocument(path) }
      document.proxyDocumentFactory = this.documentManager::getProxyDocument

      if (item.isLocked && item.canChangeLock || true) {
        this.documentConsumer.accept(document)
      } else {
        if (!item.isLocked) {
          val propertiesUi = DocPropertiesUi(
              errorUi = dialogUi::error,
              busyUi = this.paneElements.busyIndicator::accept)
          this.sceneChanger(propertiesUi.createLockSuggestionPane(document) {
            lockNode -> openDocumentWithLock(document, lockNode)
          })
        } else {
          this.sceneChanger(this.createLockWarningPage(document))
        }
      }
      document.listenLockChange(webSocket)
    }
  }

  enum class ActionOnLocked { OPEN, CANCEL }

  private fun createLockWarningPage(document: GPCloudDocument): Pane {
    val lockOwner = document.projectJson!!.lockOwner!!
    val notify = CheckBox("Show notification when lock is released").also {
      it.styleClass.add("mt-5")
      it.isSelected = true
    }
    return OptionPaneBuilder<ActionOnLocked>().run {
      i18n.rootKey = "cloud.lockWarningPane"
      titleHelpString.update(lockOwner)
      styleClass = "dlg-lock"
      styleSheets.add("/biz/ganttproject/storage/cloud/GPCloudStorage.css")
      graphic = FontAwesomeIconView(FontAwesomeIcon.LOCK)
      elements = listOf(
          OptionElementData("open", ActionOnLocked.OPEN, isSelected = true, customContent = notify),
          OptionElementData("cancel", ActionOnLocked.CANCEL)
      )


      buildDialogPane { choice ->
        when (choice) {
          ActionOnLocked.OPEN -> {
            openDocumentWithLock(document, document.projectJson.node["lock"])
            if (notify.isSelected) {
              document.status.addListener { _, _, newValue ->
                println("new value=$newValue")
                if (!newValue.locked) {
                  Platform.runLater {
                    Notifications.create().title("Project Unlocked")
                        .text("User ${newValue?.lockOwnerName ?: ""} has unlocked project ${document.fileName}")
                        .showInformation()
                  }
                }
              }
            }
          }
          ActionOnLocked.CANCEL -> {
            this@GPCloudBrowserPane.sceneChanger(this@GPCloudBrowserPane.paneElements.browserPane)
          }
        }
      }
    }
  }

  private fun openDocumentWithLock(document: GPCloudDocument, jsonLock: JsonNode?) {
    println("Lock node=$jsonLock")
    if (jsonLock != null) {
      document.lock = jsonLock
    }
    this@GPCloudBrowserPane.documentConsumer.accept(document)
  }

  private fun loadTeams(path: Path, setResult: Consumer<ObservableList<FolderItem>>, showMaskPane: Consumer<Boolean>) {
    loaderService.apply {
      busyIndicator = showMaskPane
      this.path = path
      onSucceeded = EventHandler {
        setResult.accept(value)
        showMaskPane.accept(false)
      }
      onFailed = EventHandler {
        showMaskPane.accept(false)
        when (loaderService.exception) {
          is OfflineException -> loadOfflineMirrors(setResult)
          null -> dialogUi.error("Loading failed!")
          else -> {
            val ex = loaderService.exception
            GPLogger.getLogger("GPCloud").log(Level.WARNING, "", ex)
            val errorDetails = ex.message
            dialogUi.error("Failed to load data from GanttProject Cloud $errorDetails")
          }

        }
      }
      onCancelled = EventHandler {
        showMaskPane.accept(false)
        GPLogger.log("Loading cancelled!")
      }
      restart()
    }
  }

  private fun reload() {
    this.loaderService.jsonResult.set(null)
    this.loaderService.restart()
  }
}

