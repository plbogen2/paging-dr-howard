package com.example.pagingdrhoward.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val GITHUB_API_URL = "https://api.github.com/repos/plbogen2/paging-dr-howard/releases/latest"
    private val httpClient = OkHttpClient()

    data class UpdateInfo(
        val latestVersionName: String,
        val latestBuildNumber: Int,
        val apkDownloadUrl: String,
        val releaseNotes: String,
        val hasUpdate: Boolean
    )

    /**
     * Extracts build number from version tag (e.g., "v1.0.0.1020" -> 1020, "1020" -> 1020).
     */
    fun parseBuildNumber(tag: String): Int {
        val cleaned = tag.trim().removePrefix("v").removePrefix("V")
        val parts = cleaned.split(".")
        return parts.lastOrNull()?.toIntOrNull() ?: cleaned.toIntOrNull() ?: 0
    }

    /**
     * Checks GitHub API for the latest published release.
     */
    fun checkForUpdate(
        currentBuildNumber: Int,
        onResult: (UpdateInfo?) -> Unit
    ) {
        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Failed to check for app updates: ${e.localizedMessage}")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bodyStr = response.body?.string()
                    if (!response.isSuccessful || bodyStr.isNullOrBlank()) {
                        onResult(null)
                        return
                    }

                    val json = JSONObject(bodyStr)
                    val tagName = json.optString("tag_name", "")
                    val releaseNotes = json.optString("body", "")
                    val assets = json.optJSONArray("assets")

                    var apkDownloadUrl = ""
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkDownloadUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    val latestBuild = parseBuildNumber(tagName)
                    val hasUpdate = latestBuild > currentBuildNumber && apkDownloadUrl.isNotBlank()

                    val info = UpdateInfo(
                        latestVersionName = tagName,
                        latestBuildNumber = latestBuild,
                        apkDownloadUrl = apkDownloadUrl,
                        releaseNotes = releaseNotes,
                        hasUpdate = hasUpdate
                    )
                    onResult(info)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing release update json", e)
                    onResult(null)
                }
            }
        })
    }

    /**
     * Downloads APK using Android DownloadManager and launches package installer when complete.
     */
    fun downloadAndInstallUpdate(context: Context, downloadUrl: String, versionName: String) {
        try {
            val fileName = "PagingDrHoward-$versionName.apk"
            val destinationFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("Downloading Paging Dr. Howard Update")
                setDescription("Version $versionName")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(destinationFile))
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            val onCompleteReceiver = object : BroadcastReceiver() {
                override fun onReceive(recvContext: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        try {
                            recvContext.unregisterReceiver(this)
                        } catch (e: Exception) {
                            // Ignored
                        }
                        installApk(recvContext, destinationFile)
                    }
                }
            }

            val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(onCompleteReceiver, intentFilter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(onCompleteReceiver, intentFilter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating update download", e)
        }
    }

    /**
     * Launches Android PackageInstaller for the downloaded APK using FileProvider.
     */
    private fun installApk(context: Context, apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching package installer", e)
        }
    }
}
