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
  private lateinit var bookEntity: Entity
  private lateinit var spreadEntity: Entity
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
    scene.enablePassthrough(true)

    libraryEntity = panelEntity(R.id.library_panel, 0f, 1.35f, -1.35f, 180f, true)

    bookEntity = Entity.create(
      listOf(
        Panel(R.id.book_back_panel),
        Grabbable(),
        Transform(Pose(Vector3(0f, 1.38f, -1.22f), Quaternion.fromEuler(0f, 180f, 0f))),
        Visible(false),
      )
    )

    spreadEntity = bookChild(R.id.spread_panel, 0f, 0f, -0.045f, 0f)
    toolbarEntity = bookChild(R.id.toolbar_panel, 0f, -0.56f, -0.10f, 0f)

    controller.onReadingChanged = { reading ->
      libraryEntity.setComponent(Visible(!reading))
      bookEntity.setComponent(Visible(reading))
      spreadEntity.setComponent(Visible(reading))
      toolbarEntity.setComponent(Visible(reading))
    }
  }

  override fun onDestroy() {
    controller.dispose()
    super.onDestroy()
  }

  private fun panelEntity(id: Int, x: Float, y: Float, z: Float, yaw: Float, visible: Boolean): Entity =
    Entity.create(
      listOf(
        Panel(id),
        Transform(Pose(Vector3(x, y, z), Quaternion.fromEuler(0f, yaw, 0f))),
        Visible(visible),
      )
    )

  private fun bookChild(id: Int, x: Float, y: Float, z: Float, yaw: Float): Entity =
    Entity.create(
      listOf(
        Panel(id),
        Transform(Pose(Vector3(x, y, z), Quaternion.fromEuler(0f, yaw, 0f))),
        TransformParent(bookEntity),
        Visible(false),
      )
    )

  override fun registerPanels(): List<PanelRegistration> = listOf(
    composePanel(R.id.library_panel, 1.02f, 0.74f) { LibraryPanel(controller) },
    composePanel(R.id.book_back_panel, 1.26f, 0.90f) { BookBackPanel() },
    composePanel(R.id.spread_panel, 1.16f, 0.82f) { BookSpreadPanel(controller) },
    composePanel(R.id.toolbar_panel, 1.18f, 0.19f) { ReaderToolbar(controller) },
  )

  private fun composePanel(
    id: Int,
    width: Float,
    height: Float,
    content: @androidx.compose.runtime.Composable () -> Unit,
  ): PanelRegistration =
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
