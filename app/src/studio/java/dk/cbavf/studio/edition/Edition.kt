package dk.cbavf.studio.edition

import androidx.compose.runtime.Composable

/** The studio edition: the app as it is. */
@Composable
fun EditionWrapper(content: @Composable () -> Unit) = content()
