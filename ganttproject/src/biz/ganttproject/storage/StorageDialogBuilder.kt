/*
Copyright 2019 BarD Software s.r.o

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
package biz.ganttproject.storage

import biz.ganttproject.FXUtil
import biz.ganttproject.storage.cloud.GPCloudStorageOptions
import com.google.common.base.Preconditions
import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.ToggleButton
import javafx.scene.input.KeyCode
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import net.sourceforge.ganttproject.IGanttProject
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.document.DocumentManager
import net.sourceforge.ganttproject.document.ReadOnlyProxyDocument
import net.sourceforge.ganttproject.gui.ProjectUIFacade
import net.sourceforge.ganttproject.gui.UIFacade
import net.sourceforge.ganttproject.language.GanttLanguage
import org.controlsfx.control.NotificationPane
import org.controlsfx.control.SegmentedButton
import java.io.IOException
import java.util.*
import java.util.function.Consumer
import javax.swing.SwingUtilities

/**
 * @author dbarashev@bardsoftware.com
 */
class StorageDialogBuilder(private val myProject: IGanttProject, projectUi: ProjectUIFacade, documentManager: DocumentManager, cloudStorageOptions: GPCloudStorageOptions) {
  private val myCloudStorageOptions: GPCloudStorageOptions = Preconditions.checkNotNull(cloudStorageOptions)
  private val myDocumentReceiver: Consumer<Document>
  private val myDocumentUpdater: Consumer<Document>
  private var myNotificationPane: NotificationPane? = null
  private var myOpenStorage: Node? = null
  private var mySaveStorage: Pane? = null
  private var myScene: Scene? = null
  private var myDialog: UIFacade.Dialog? = null
//  private var myJfxPanel: JFXPanel? = null

  private val myDialogUi = object : DialogUi {

    override fun error(e: Throwable) {
      val notificationText = TextArea(e.message)
      notificationText.isWrapText = true
      myNotificationPane!!.content = notificationText
      myNotificationPane!!.show()
    }

    override fun error(message: String) {
      myNotificationPane!!.text = message
      myNotificationPane!!.show()
    }

    override fun message(message: String) {
      val notificationText = TextArea(message)
      notificationText.isWrapText = true
      notificationText.prefRowCount = 3
      notificationText.styleClass.add("info")
      myNotificationPane!!.graphic = notificationText
      myNotificationPane!!.show()
    }

    override fun close() {
      myDialog!!.hide()
    }

    override fun resize() {
      //
//      if (myJfxPanel != null) {
//        myJfxPanel!!.scene = null
//        myJfxPanel!!.scene = myScene
//        SwingUtilities.invokeLater { myDialog!!.layout() }
//      }
    }
  }

  fun setDialog(dlg: UIFacade.Dialog) {
    myDialog = dlg
    myScene!!.setOnKeyPressed { keyEvent ->
      if (keyEvent.code == KeyCode.ESCAPE) {
        dlg.hide()
      }
    }
//    dlg.show()
    Platform.runLater {
      //      myJfxPanel!!.scene = null
//      myJfxPanel!!.scene = myScene
    }
  }


  init {
    myDocumentReceiver = Consumer { document: Document ->
      SwingUtilities.invokeLater {
        try {
          projectUi.openProject(documentManager.getProxyDocument(document), myProject)
        } catch (e: IOException) {
          e.printStackTrace()
        } catch (e: Document.DocumentException) {
          e.printStackTrace()
        }
      }
    }
    myDocumentUpdater = Consumer { document ->
      SwingUtilities.invokeLater {
        if (myProject.document == null) {
          myProject.document = documentManager.getProxyDocument(document)
        } else {
          myProject.document.setMirror(document)
        }
        projectUi.saveProject(myProject)
      }
    }
  }

  fun build(): Parent {
    val borderPane = BorderPane()
    borderPane.styleClass.add("body")

    borderPane.styleClass.add("pane-storage")
    borderPane.center = Pane()
    val btnSave = ToggleButton(GanttLanguage.getInstance().getText("myProjects.save"))
    val btnOpen = ToggleButton(GanttLanguage.getInstance().getText("myProjects.open"))

    run {
      val titleBox = VBox()
      titleBox.styleClass.add("title-box")
      val projectName = Label(myProject.projectName)

      val buttonBar = SegmentedButton()
      buttonBar.styleClass.add(SegmentedButton.STYLE_CLASS_DARK)
      btnOpen.addEventHandler(ActionEvent.ACTION) { showOpenStorageUi(borderPane) }
      //
      btnSave.addEventHandler(ActionEvent.ACTION) { showSaveStorageUi(borderPane) }
      buttonBar.buttons.addAll(btnSave, btnOpen)
      val buttonWrapper = HBox()
      buttonWrapper.styleClass.addAll("open-save-buttons")
      buttonWrapper.children.add(buttonBar)

      titleBox.children.addAll(projectName, buttonWrapper)
      borderPane.top = titleBox
    }

    borderPane.stylesheets.add("biz/ganttproject/storage/StorageDialog.css")
//    myScene = Scene(borderPane)
//    myScene!!.stylesheets
//    val jfxPanel = JFXPanel()
//    jfxPanel.scene = myScene

    if (myProject.isModified) {
      btnSave.fire()
    } else {
      btnOpen.fire()
    }

    return borderPane
  }

  private fun showOpenStorageUi(container: BorderPane) {
    if (myOpenStorage == null) {
      val storagePane = buildStoragePane(Mode.OPEN)
      myNotificationPane = NotificationPane(storagePane)
      myNotificationPane!!.styleClass.addAll(
          NotificationPane.STYLE_CLASS_DARK)
      myOpenStorage = myNotificationPane
    }
    FXUtil.transitionCenterPane(container, myOpenStorage) { myDialogUi.resize() }
  }

  private fun showSaveStorageUi(container: BorderPane) {
    if (mySaveStorage == null) {
      mySaveStorage = buildStoragePane(Mode.SAVE)
    }
    FXUtil.transitionCenterPane(container, mySaveStorage) { myDialogUi.resize() }
  }

  private fun buildStoragePane(mode: Mode): Pane {
    if (myProject.document != null) {
      val storagePane = StoragePane(myCloudStorageOptions, myProject.documentManager, ReadOnlyProxyDocument(myProject.document), myDocumentReceiver, myDocumentUpdater, myDialogUi)
      return storagePane.buildStoragePane(mode)
    } else {
      return Pane(Label("No document!"))
    }
  }

  enum class Mode {
    OPEN, SAVE
  }

  interface DialogUi {
    fun close()

    fun resize()

    fun error(e: Throwable)

    fun error(message: String)

    fun message(message: String)
  }

  interface Ui {
    val category: String

    val id: String
      get() = category

    val name: String

    fun createUi(): Pane

    fun createSettingsUi(): Optional<Pane>
  }
}
