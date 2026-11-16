package dev.blackorange.mdm

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

/** The Rust core (engine + torrents). Everything goes through two JNI calls with JSON. */
object Native {
    init {
        System.loadLibrary("mdm_android_core")
    }

    @JvmStatic
    private external fun initTls(context: Context)

    @JvmStatic
    private external fun init(dataDir: String, downloadDir: String): String

    @JvmStatic
    private external fun call(method: String, args: String): String

    private val ready = CountDownLatch(1)

    @Volatile
    var initError: String = ""
        private set

    /** Starts the core on a background thread; [request] waits until it is up. */
    fun start(context: Context) {
        val app = context.applicationContext
        Thread {
            initError = try {
                val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
                initTls(app)
                init(app.filesDir.absolutePath, dir.absolutePath)
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            ready.countDown()
        }.start()
    }

    /** Blocks: call from a background thread only. Returns the core's JSON answer. */
    fun request(method: String, args: JSONObject = JSONObject()): String {
        ready.await()
        if (initError.isNotEmpty()) return JSONObject().put("error", initError).toString()
        return call(method, args.toString())
    }

    fun add(link: String) = request("add", JSONObject().put("link", link))

    fun addTorrentFile(path: String) = request("add_torrent_file", JSONObject().put("path", path))

    /** New downloads go to [path] (existing ones keep their folder). */
    fun setDownloadDir(path: String) = request("set_download_dir", JSONObject().put("path", path))

    fun pause(id: String) = request("pause", JSONObject().put("id", id))

    fun resume(id: String) = request("resume", JSONObject().put("id", id))

    fun remove(id: String, deleteFiles: Boolean) =
        request("remove", JSONObject().put("id", id).put("delete_files", deleteFiles))

    /** The list; null when the core answered with an error. */
    fun list(): List<Item>? {
        val text = request("list")
        if (!text.startsWith("[")) return null
        val arr = JSONArray(text)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Item(
                id = o.getString("id"),
                kind = o.getString("kind"),
                name = o.getString("name"),
                status = o.getString("status"),
                done = o.optLong("done"),
                total = if (o.isNull("total")) -1 else o.optLong("total", -1),
                speed = o.optLong("speed"),
                error = if (o.isNull("error")) null else o.optString("error"),
            )
        }
    }

    /** The error in an answer, or null. */
    fun errorOf(answer: String): String? =
        runCatching { JSONObject(answer).optString("error").ifEmpty { null } }.getOrNull()
}

data class Item(
    val id: String,
    val kind: String,
    val name: String,
    val status: String,
    val done: Long,
    val total: Long,
    val speed: Long,
    val error: String?,
) {
    val active get() = status == "downloading" || status == "connecting" ||
        status == "resolving" || status == "checking"
}
