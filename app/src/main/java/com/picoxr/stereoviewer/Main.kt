package com.picoxr.stereoviewer

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.pico.spatial.ui.platform.resize.CaptionBarType
import com.picoxr.stereoviewer.content.FullSpacePage
import com.picoxr.stereoviewer.content.FullSpaceDockPage
import com.picoxr.stereoviewer.content.HomePage
import com.picoxr.stereoviewer.content.MediaPreviewPage

const val MILESTONE_ZERO_STAGE_ID = "Milestone0Stage"
const val MAIN_WINDOW_ID = "YourPlanarWindowContainer"
const val MEDIA_PREVIEW_WINDOW_ID = "MediaPreviewWindow"
const val FULL_SPACE_DOCK_WINDOW_ID = "FullSpaceDockWindow"
const val FULL_SPACE_DOCK_WINDOW_TAG = "ActiveFullSpaceDock"

fun mainApp(scope: SpatialAppScope) = with(scope) {
    DefaultWindowContainer {
        PicoTheme {
            HomePage()
        }
    }
    WindowContainer(
        id = MEDIA_PREVIEW_WINDOW_ID,
        form = Form.Planar,
        defaultSize = WindowContainerSize(width = 900.dp, height = 560.dp),
        enableMaterialBackground = false,
    ) {
        PicoTheme {
            MediaPreviewPage(bundle)
        }
    }
    WindowContainer(
        id = FULL_SPACE_DOCK_WINDOW_ID,
        form = Form.Planar,
        defaultSize = WindowContainerSize(width = 760.dp, height = 176.dp),
        enableMaterialBackground = true,
        defaultCaptionBarType = CaptionBarType.Default,
    ) {
        PicoTheme {
            FullSpaceDockPage()
        }
    }
    Stage(id = MILESTONE_ZERO_STAGE_ID) {
        PicoTheme {
            FullSpacePage()
        }
    }
}
