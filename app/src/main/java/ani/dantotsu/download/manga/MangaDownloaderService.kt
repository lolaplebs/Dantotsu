package ani.dantotsu.download.manga

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.R
import ani.dantotsu.download.Download
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.media.Media
import ani.dantotsu.media.manga.ImageData
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import com.google.gson.Gson
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_DOWNLOADER_PROGRESS
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.content.ContextCompat
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_FAILED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_FINISHED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_STARTED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.EXTRA_CHAPTER_NUMBER
import ani.dantotsu.snackString
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class MangaDownloaderService : Service() {

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var builder: NotificationCompat.Builder
    private val downloadsManager: DownloadsManager = Injekt.get<DownloadsManager>()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    var isCurrentlyProcessing = false

    override fun onBind(intent: Intent?): IBinder? {
        // This is only required for bound services.
        return null
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        builder = NotificationCompat.Builder(this, CHANNEL_DOWNLOADER_PROGRESS).apply {
            setContentTitle("Manga Download Progress")
            setSmallIcon(R.drawable.ic_round_download_24)
            priority = NotificationCompat.PRIORITY_DEFAULT
            setOnlyAlertOnce(true)
            setProgress(0, 0, false)
        }
        startForeground(NOTIFICATION_ID, builder.build())
        registerReceiver(cancelReceiver, IntentFilter(ACTION_CANCEL_DOWNLOAD))
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceDataSingleton.downloadQueue.clear()
        downloadJobs.clear()
        ServiceDataSingleton.isServiceRunning = false
        unregisterReceiver(cancelReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        snackString("Download started")
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serviceScope.launch {
            mutex.withLock {
                if (!isCurrentlyProcessing) {
                    isCurrentlyProcessing = true
                    processQueue()
                    isCurrentlyProcessing = false
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun processQueue() {
        CoroutineScope(Dispatchers.Default).launch {
            while (ServiceDataSingleton.downloadQueue.isNotEmpty()) {
                val task = ServiceDataSingleton.downloadQueue.poll()
                if (task != null) {
                    val job = launch { download(task) }
                    mutex.withLock {
                        downloadJobs[task.chapter] = job
                    }
                    job.join() // Wait for the job to complete before continuing to the next task
                    mutex.withLock {
                        downloadJobs.remove(task.chapter)
                    }
                    updateNotification() // Update the notification after each task is completed
                }
                if (ServiceDataSingleton.downloadQueue.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        stopSelf() // Stop the service when the queue is empty
                    }
                }
            }
        }
    }

    fun cancelDownload(chapter: String) {
        CoroutineScope(Dispatchers.Default).launch {
            mutex.withLock {
                downloadJobs[chapter]?.cancel()
                downloadJobs.remove(chapter)
                ServiceDataSingleton.downloadQueue.removeAll { it.chapter == chapter }
                updateNotification() // Update the notification after cancellation
            }
        }
    }

    private fun updateNotification() {
        // Update the notification to reflect the current state of the queue
        val pendingDownloads = ServiceDataSingleton.downloadQueue.size
        val text = if (pendingDownloads > 0) {
            "Pending downloads: $pendingDownloads"
        } else {
            "All downloads completed"
        }
        builder.setContentText(text)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    suspend fun download(task: DownloadTask) {
        withContext(Dispatchers.Main) {
            if (ContextCompat.checkSelfPermission(
                    this@MangaDownloaderService,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    this@MangaDownloaderService,
                    "Please grant notification permission",
                    Toast.LENGTH_SHORT
                ).show()
                broadcastDownloadFailed(task.chapter)
                return@withContext
            }

            val deferredList = mutableListOf<Deferred<Bitmap?>>()
            builder.setContentText("Downloading ${task.title} - ${task.chapter}")
            notificationManager.notify(NOTIFICATION_ID, builder.build())

            // Loop through each ImageData object from the task
            var farthest = 0
            for ((index, image) in task.imageData.withIndex()) {
                // Limit the number of simultaneous downloads from the task
                if (deferredList.size >= task.simultaneousDownloads) {
                    // Wait for all deferred to complete and clear the list
                    deferredList.awaitAll()
                    deferredList.clear()
                }

                // Download the image and add to deferred list
                val deferred = async(Dispatchers.IO) {
                    var bitmap: Bitmap? = null
                    var retryCount = 0

                    while (bitmap == null && retryCount < task.retries) {
                        bitmap = image.fetchAndProcessImage(
                            image.page,
                            image.source,
                            this@MangaDownloaderService
                        )
                        retryCount++
                    }

                    // Cache the image if successful
                    if (bitmap != null) {
                        saveToDisk("$index.jpg", bitmap, task.title, task.chapter)
                    }
                    farthest++
                    builder.setProgress(task.imageData.size, farthest, false)
                    notificationManager.notify(NOTIFICATION_ID, builder.build())

                    bitmap
                }

                deferredList.add(deferred)
            }

            // Wait for any remaining deferred to complete
            deferredList.awaitAll()

            builder.setContentText("${task.title} - ${task.chapter} Download complete")
                .setProgress(0, 0, false)
            notificationManager.notify(NOTIFICATION_ID, builder.build())

            saveMediaInfo(task)
            downloadsManager.addDownload(Download(task.title, task.chapter, Download.Type.MANGA))
            //downloadsManager.exportDownloads(Download(task.title, task.chapter, Download.Type.MANGA))
            broadcastDownloadFinished(task.chapter)
            snackString("${task.title} - ${task.chapter} Download finished")
        }
    }


    private fun saveToDisk(fileName: String, bitmap: Bitmap, title: String, chapter: String) {
        try {
            // Define the directory within the private external storage space
            val directory = File(
                this.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "Dantotsu/Manga/$title/$chapter"
            )

            if (!directory.exists()) {
                directory.mkdirs()
            }

            // Create a file reference within that directory for your image
            val file = File(directory, fileName)

            // Use a FileOutputStream to write the bitmap to the file
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }


        } catch (e: Exception) {
            println("Exception while saving image: ${e.message}")
            snackString("Exception while saving image: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    fun saveMediaInfo(task: DownloadTask) {
        GlobalScope.launch(Dispatchers.IO) {
            val directory = File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "Dantotsu/Manga/${task.title}"
            )
            if (!directory.exists()) directory.mkdirs()

            val file = File(directory, "media.json")
            val gson = GsonBuilder()
                .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> {
                    SChapterImpl() // Provide an instance of SChapterImpl
                })
                .create()
            val mediaJson = gson.toJson(task.sourceMedia)
            val media = gson.fromJson(mediaJson, Media::class.java)
            if (media != null) {
                media.cover = media.cover?.let { downloadImage(it, directory, "cover.jpg") }
                media.banner = media.banner?.let { downloadImage(it, directory, "banner.jpg") }

                val jsonString = gson.toJson(media)
                withContext(Dispatchers.Main) {
                    file.writeText(jsonString)
                }
            }
        }
    }


    suspend fun downloadImage(url: String, directory: File, name: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        println("Downloading url $url")
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
            }

            val file = File(directory, name)
            FileOutputStream(file).use { output ->
                connection.inputStream.use { input ->
                    input.copyTo(output)
                }
            }
            return@withContext file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MangaDownloaderService, "Exception while saving ${name}: ${e.message}", Toast.LENGTH_LONG).show()
            }
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun broadcastDownloadStarted(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_STARTED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFinished(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FINISHED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFailed(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FAILED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CANCEL_DOWNLOAD) {
                val chapter = intent.getStringExtra(EXTRA_CHAPTER)
                chapter?.let {
                    cancelDownload(it)
                }
            }
        }
    }


    data class DownloadTask(
        val title: String,
        val chapter: String,
        val imageData: List<ImageData>,
        val sourceMedia: Media? = null,
        val retries: Int = 2,
        val simultaneousDownloads: Int = 2,
    )

    companion object {
        private const val NOTIFICATION_ID = 1103
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"
        const val EXTRA_CHAPTER = "extra_chapter"
    }
}

object ServiceDataSingleton {
    var imageData: List<ImageData> = listOf()
    var sourceMedia: Media? = null
    var downloadQueue: Queue<MangaDownloaderService.DownloadTask> = ConcurrentLinkedQueue()
    @Volatile
    var isServiceRunning: Boolean = false
}