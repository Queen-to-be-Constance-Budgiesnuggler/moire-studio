package dk.cbavf.studio.bridge

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dk.cbavf.studio.bridge.model.Candidate
import dk.cbavf.studio.bridge.model.ExternalApp
import dk.cbavf.studio.bridge.model.Matcher
import dk.cbavf.studio.bridge.model.PendingSend
import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.ProjectStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Images added to shared storage after [afterSec], newest last, each matched to a pending send.
 * The studio's own exports are skipped by owner package.
 */
fun scanNewImages(context: Context, afterSec: Long, sends: List<PendingSend>, handled: Set<Long>): List<Candidate> {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.OWNER_PACKAGE_NAME,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
    )
    val out = mutableListOf<Candidate>()
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Images.Media.DATE_ADDED} > ?",
        arrayOf(afterSec.toString()),
        "${MediaStore.Images.Media.DATE_ADDED} ASC",
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val owner = c.getString(3)
            if (id in handled || owner == context.packageName) continue
            val added = c.getLong(4)
            val app = ExternalApp.ofPackage(owner) ?: dk.cbavf.studio.bridge.model.WatchFolder.of(c.getString(2) ?: "").likelyApp
            val (match, sure) = Matcher.match(added, app, sends)
            out += Candidate(
                mediaId = id,
                uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString(),
                displayName = c.getString(1) ?: "image",
                relativePath = c.getString(2) ?: "",
                ownerPackage = owner,
                addedAtSec = added,
                widthPx = c.getInt(5),
                heightPx = c.getInt(6),
                match = match,
                sure = sure,
            )
        }
    }
    return out
}

/** Emits whenever shared images change, while collected (i.e. while the app is open). */
fun mediaChanges(context: Context): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    awaitClose { context.contentResolver.unregisterContentObserver(observer) }
}

/**
 * Copies a shared image into the project as a new asset (originals are never touched).
 * [parentId] links it into the generation tree.
 */
fun importImage(
    context: Context,
    store: ProjectStore,
    uri: Uri,
    operation: String,
    parentId: String?,
    device: String,
): AssetRecord? {
    val resolver = context.contentResolver
    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri))?.lowercase() ?: "jpg"
    val tmp = File(store.tempDir, "import.$ext")
    resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(tmp.path, bounds)
    if (bounds.outWidth <= 0) {
        tmp.delete()
        return null
    }
    return store.addAssetFile(tmp, ext, listOfNotNull(parentId), operation, device, bounds.outWidth, bounds.outHeight, recipe = null)
}

/** content:// uri other apps can read for a project file. */
fun shareUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, context.packageName + ".files", file)

/** Installed package for [app], if any. */
fun installedPackage(context: Context, app: ExternalApp): String? =
    app.packages.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

/**
 * Hands an asset to an app: a photo editor via EDIT and a share target via SEND, both through Android's chooser, so the person
 * picks the app. Returns false if nothing could take it.
 */
fun sendAsset(context: Context, store: ProjectStore, asset: AssetRecord, app: ExternalApp): Boolean {
    val uri = shareUri(context, store.fileOf(asset))
    val mime = if (asset.isVideo) "video/mp4" else context.contentResolver.getType(uri) ?: "image/*"
    val intent = when (app) {
        ExternalApp.Gallery -> Intent.createChooser(Intent(Intent.ACTION_EDIT).setDataAndType(uri, mime), "Edit with")
        else -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri), "Send to")
    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    // A named third-party app (FV-5) is addressed directly; the other kinds go through the chooser.
    if (app.packages.isNotEmpty()) {
        intent.setPackage(installedPackage(context, app) ?: return false)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/**
 * Opens an app with nothing attached (FV-5 has no control API: launch, then watch its folder). The photo editor kind opens
 * whichever gallery app the phone uses.
 */
fun launchApp(context: Context, app: ExternalApp): Boolean {
    if (app == ExternalApp.Gallery) {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); true } catch (e: ActivityNotFoundException) { false }
    }
    val pkg = installedPackage(context, app) ?: return false
    val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
    context.startActivity(intent)
    return true
}
