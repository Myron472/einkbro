package de.baumann.browser.task

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import android.widget.ProgressBar
import androidx.core.content.FileProvider.getUriForFile
import de.baumann.browser.unit.HelperUnit.fileName
import de.baumann.browser.unit.HelperUnit.needGrantStoragePermission
import de.baumann.browser.unit.ViewUnit.capture
import de.baumann.browser.unit.ViewUnit.getDensity
import de.baumann.browser.unit.ViewUnit.getWindowWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.*

class SaveScreenshotTask(
    private val context: Context,
    private val webView: WebView,
) {

    suspend fun execute() {
        val url = webView.url ?: return

        if (needGrantStoragePermission(context as Activity)) return

        // progress dialog
        val progressDialog = AlertDialog.Builder(context).setView(ProgressBar(context)).show()

        //background
        val title = fileName(url)
        val windowWidth = getWindowWidth(context).toFloat()
        val contentHeight = webView.contentHeight * getDensity(context)
        val uri = captureAndSaveImage(webView, windowWidth, contentHeight, title) ?: return

        // post
        progressDialog.dismiss()
        showSavedScreenshot(uri)
    }

    suspend fun captureAndSaveImage(webView: WebView, width: Float, height: Float, name: String): Uri? {
        val bitmap = capture(webView, width, height)
        var uri: Uri? = null
        withContext(Dispatchers.IO) {
            val fos: OutputStream
            try {
                uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/" + "Screenshots/")
                    }
                    val resolver: ContentResolver = context.contentResolver
                    val nonNullUri =
                        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                            ?: return@withContext null
                    fos = resolver.openOutputStream(nonNullUri) ?: return@withContext null
                    nonNullUri
                } else {
                    val imagesDir = Environment.getExternalStoragePublicDirectory("Screenshots").toString() + File.separator
                    val file = File(imagesDir)
                    if (!file.exists()) { file.mkdir() }

                    val image = File(imagesDir, "$name.jpg")
                    fos = FileOutputStream(image)
                    if (Build.VERSION.SDK_INT < 24) Uri.fromFile(image)
                    // something wrong with file://xxxx
                    else getUriForFile(context.applicationContext, context.packageName + ".fileprovider", image)
                }

                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                fos.flush()
                fos.close()
            } catch (exception: IOException) {
                return@withContext null
            }
        }

        return uri
    }

    private fun showSavedScreenshot(uri: Uri) {
        val intent = Intent().apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(intent)
    }
}