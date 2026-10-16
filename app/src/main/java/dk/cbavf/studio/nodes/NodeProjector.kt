package dk.cbavf.studio.nodes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import dk.cbavf.studio.sequence.FullScreenWindow

/**
 * The Projector node's output: the whole screen, black around the picture, full brightness and
 * the screen kept on. Pixels are not smoothed, so a screen-sized moiré stays exact. Tap or Back
 * to close.
 */
@Composable
fun NodeProjector(image: ImageBitmap?, onClose: () -> Unit) {
    FullScreenWindow(null)
    BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
        } else {
            Text("Rendering… (nothing connected shows black)", color = Color.Gray)
        }
    }
}
