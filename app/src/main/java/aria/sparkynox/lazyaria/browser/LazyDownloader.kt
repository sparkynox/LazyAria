package aria.sparkynox.lazyaria.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import aria.sparkynox.lazyaria.core.Prefs

object LazyDownloader {

    fun enqueue(context: Context, url: String, mime: String?, contentDisposition: String?) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cookie = CookieManager.getInstance().getCookie(url)
        val filename = URLUtil.guessFileName(url, contentDisposition, mime)

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mime)
            addRequestHeader("cookie", cookie)
            addRequestHeader("User-Agent", resolveUA())
            setDescription("Downloading via LazyAria")
            setTitle(filename)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            val dir = if (Prefs.downloadPath.isNotBlank()) {
                Uri.parse(Prefs.downloadPath)
                null // handled below
            } else {
                null
            }
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        }

        dm.enqueue(request)
    }

    private fun resolveUA(): String {
        return if (Prefs.desktopMode)
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36"
        else
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Mobile Safari/537.36"
    }
}
