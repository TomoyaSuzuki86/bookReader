package com.tomoya.bookreader

import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.*
import com.meta.spatial.vr.VRFeature

class BookReaderActivity : AppSystemActivity() {
  private lateinit var controller: ReaderController
  private lateinit var libraryEntity: Entity
  private lateinit var leftPageEntity: Entity
  private lateinit var rightPageEntity: Entity
  private lateinit var toolbarEntity: Entity

  override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

  override fun onCreate(savedInstanceState: Bundle?) {
    controller = ReaderController(this)
    super.onCreate(savedInstanceState)
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setViewOrigin(0f, 0f, 0f, 0f)

    libraryEntity = panelEntity(R.id.library_panel, 0f, 1.35f, -1.35f, 180f, true)
    leftPageEntity = panelEntity(R.id.left_page_panel, -0.305f, 1.38f, -1.18f, 176f, false)
    rightPageEntity = panelEntity(R.id.right_page_panel, 0.305f, 1.38f, -1.18f, 184f, false)
    toolbarEntity = panelEntity(R.id.toolbar_panel, 0f, 0.82f, -1.12f, 180f, false)

    controller.onReadingChanged = { reading ->
      libraryEntity.setComponent(Visible(!reading))
      leftPageEntity.setComponent(Visible(reading))
      rightPageEntity.setComponent(Visible(reading))
      toolbarEntity.setComponent(Visible(reading))
    }
  }

  private fun panelEntity(id: Int, x: Float, y: Float, z: Float, yaw: Float, visible: Boolean): Entity =
    Entity.create(listOf(Panel(id), Transform(Pose(Vector3(x, y, z), Quaternion.fromEuler(0f, yaw, 0f))), Visible(visible)))

  override fun registerPanels(): List<PanelRegistration> = listOf(
    composePanel(R.id.library_panel, 1.0f, 0.72f) { LibraryPanel(controller) },
    composePanel(R.id.left_page_panel, 0.58f, 0.82f) { PagePanel(controller, true) },
    composePanel(R.id.right_page_panel, 0.58f, 0.82f) { PagePanel(controller, false) },
    composePanel(R.id.toolbar_panel, 1.18f, 0.16f) { ReaderToolbar(controller) },
  )

  private fun composePanel(id: Int, width: Float, height: Float, content: @androidx.compose.runtime.Composable () -> Unit): PanelRegistration =
    ComposeViewPanelRegistration(
      id,
      composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { content() } } },
      settingsCreator = {
        UIPanelSettings(
          shape = QuadShapeOptions(width = width, height = height),
          style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
          display = DpPerMeterDisplayOptions(),
        )
      },
    )
}
