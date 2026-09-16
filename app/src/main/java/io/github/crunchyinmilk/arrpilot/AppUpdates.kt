package io.github.crunchyinmilk.arrpilot

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppRelease(val version: String, val url: String, val size: Long, val digest: String)

class AppUpdates(private val context: Context) {
    private fun connection(address: String) = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "ArrPilot/${BuildConfig.VERSION_NAME}")
    }

    fun latest(): AppRelease? {
        val conn = connection("https://api.github.com/repos/crunchy-in-milk/ArrPilot/releases/latest")
        try {
            if (conn.responseCode == 404) return null
            check(conn.responseCode == 200) { "GitHub returned ${conn.responseCode}. Please try again later." }
            val release = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
            val version = release.getString("tag_name").removePrefix("v")
            if (!newer(version, BuildConfig.VERSION_NAME)) return null
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == "ArrPilot-$version.apk" }
                ?: error("The latest release does not have an installable APK yet.")
            val url = asset.getString("browser_download_url")
            check(url.startsWith("https://github.com/crunchy-in-milk/ArrPilot/releases/download/")) { "Unexpected update source" }
            return AppRelease(version, url, asset.getLong("size"), asset.optString("digest"))
        } finally { conn.disconnect() }
    }

    fun download(release: AppRelease): File {
        check(release.size in 1..50_000_000) { "Unexpected update size" }
        val partial = File(context.cacheDir, "update.part")
        val target = File(context.cacheDir, "update.apk")
        val conn = connection(release.url)
        try {
            check(conn.responseCode == 200) { "Download failed (${conn.responseCode})" }
            var count = 0L
            conn.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) error("Download cancelled")
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    check(count <= release.size) { "Unexpected update size" }
                    output.write(buffer, 0, read)
                }
            } }
            check(count == release.size) { "Incomplete download. Please retry." }
            if (release.digest.startsWith("sha256:")) {
                val hash = MessageDigest.getInstance("SHA-256")
                partial.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                }
                check(hash.digest().joinToString("") { "%02x".format(it) } == release.digest.removePrefix("sha256:")) { "Update checksum does not match" }
            }
            verify(partial, release.version)
            check(partial.renameTo(target)) { "Could not save update" }
            return target
        } finally { conn.disconnect(); partial.delete() }
    }

    @Suppress("DEPRECATION")
    fun verify(file: File, expectedVersion: String? = null) {
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
            ?: error("Invalid APK")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        check(apk.packageName == context.packageName) { "Update is for a different app" }
        check(apk.versionCode > installed.versionCode) { "Update must have a higher Android version code" }
        check(expectedVersion == null || apk.versionName == expectedVersion) { "Update version does not match release" }
        val signatures = apk.signatures.orEmpty().map { it.toCharsString() }.toSet()
        check(signatures.isNotEmpty() && signatures == installed.signatures.orEmpty().map { it.toCharsString() }.toSet()) { "Update signature does not match ArrPilot" }
    }

    companion object {
        fun newer(candidate: String, installed: String): Boolean {
            fun parts(value: String): List<Int>? = if (value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
                value.split('.').map { it.toIntOrNull() ?: return null }
            } else null
            val a = parts(candidate) ?: return false
            val b = parts(installed) ?: return false
            for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
            return false
        }
    }
}
