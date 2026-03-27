/**************************************************************************/
/*  GodotObbPlugin.kt                                                     */
/**************************************************************************/
/*                         This file is part of:                          */
/*                             GODOT ENGINE                               */
/*                        https://godotengine.org                         */
/**************************************************************************/
/* Copyright (c) 2014-present Godot Engine contributors (see AUTHORS.md). */
/* Copyright (c) 2007-2014 Juan Linietsky, Ariel Manzur.                  */
/*                                                                        */
/* Permission is hereby granted, free of charge, to any person obtaining  */
/* a copy of this software and associated documentation files (the        */
/* "Software"), to deal in the Software without restriction, including    */
/* without limitation the rights to use, copy, modify, merge, publish,    */
/* distribute, sublicense, and/or sell copies of the Software, and to     */
/* permit persons to whom the Software is furnished to do so, subject to  */
/* the following conditions:                                              */
/*                                                                        */
/* The above copyright notice and this permission notice shall be         */
/* included in all copies or substantial portions of the Software.        */
/*                                                                        */
/* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,        */
/* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF     */
/* MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. */
/* IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY   */
/* CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,   */
/* TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE      */
/* SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.                 */
/**************************************************************************/

package org.godotengine.plugin.obb

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Messenger
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.vending.expansion.downloader.DownloadProgressInfo
import com.google.android.vending.expansion.downloader.DownloaderClientMarshaller
import com.google.android.vending.expansion.downloader.DownloaderServiceMarshaller
import com.google.android.vending.expansion.downloader.Helpers
import com.google.android.vending.expansion.downloader.IDownloaderClient
import com.google.android.vending.expansion.downloader.IStub
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.utils.ProcessPhoenix
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

/**
 * Support for the Play store obb API in Godot [was deprecated and removed](https://github.com/godotengine/godot/pull/118283)
 * in Godot 4.7 and the logic was moved to this plugin.
 *
 * The API itself was deprecated by Google in [August 2021](https://developer.android.com/google/play/expansion-files)
 * in favor of [Android App Bundle](https://developer.android.com/guide/app-bundle),
 * [Play feature delivery](https://developer.android.com/guide/playcore/feature-delivery) and
 * [Play asset delivery](http://developer.android.com/guide/playcore/asset-delivery).
 * As such, this plugin exists primarily to support existing projects in the Play store that still
 * have a dependency on the Play store obb API.
 */
class GodotObbPlugin(godot: Godot) : GodotPlugin(godot), IDownloaderClient {

    companion object {
        private val TAG = GodotObbPlugin::class.java.simpleName

        private const val COMMAND_LINES_FILE_NAME = "_obb_cl_"
        private const val COMMAND_LINES_ARG_SEPARATOR = "|"
    }

    private var mDownloaderClientStub: IStub? = null

    private var mStatusText: TextView? = null
    private var mProgressFraction: TextView? = null
    private var mProgressPercent: TextView? = null
    private var mAverageSpeed: TextView? = null
    private var mTimeRemaining: TextView? = null
    private var mPB: ProgressBar? = null
    private var mDashboard: View? = null
    private var mCellMessage: View? = null
    private var mPauseButton: Button? = null

    private var mState = 0

    override fun getPluginName() = BuildConfig.GODOT_PLUGIN_NAME

    override fun getCommandLineParams(unmodifiableOriginalCommandLineParams: List<String>): List<String> {
        val commandLines = parseAssetsCommandLine()
        commandLines.addAll(unmodifiableOriginalCommandLineParams)

        // check for apk expansion API
        var mainPackMd5: String? = null
        var mainPackKey: String? = null
        var useApkExpansion = false
        var i = 0
        while (i < commandLines.size) {
            val hasExtra: Boolean = i < commandLines.size - 1
            val param = commandLines[i]
            if (param == "--use_apk_expansion") {
                useApkExpansion = true
            } else if (hasExtra && param == "--apk_expansion_md5") {
                mainPackMd5 = commandLines[i + 1]
                i++
            } else if (hasExtra && param == "--apk_expansion_key") {
                mainPackKey = commandLines[i + 1]
                val prefs = context.getSharedPreferences(
                    "app_data_keys",
                    Context.MODE_PRIVATE
                )
                prefs.edit().putString("store_public_key", mainPackKey).apply()
                i++
            }
            i++
        }

        var expansionPackPath = ""
        var packValid = true
        if (useApkExpansion && mainPackMd5 != null && mainPackKey != null) {
            // Build the full path to the app's expansion files.
            try {
                expansionPackPath = Helpers.getSaveFilePath(context)
                expansionPackPath += "/main." + context.packageManager.getPackageInfo(
                    context.packageName,
                    0
                ).versionCode + "." + context.packageName + ".obb"
            } catch (e: java.lang.Exception) {
                Log.e(TAG, "Unable to build full path to the app's expansion files", e)
            }
            val f = File(expansionPackPath)
            if (!f.exists()) {
                Log.e(TAG, "Expansion pack '$expansionPackPath' doesn't exist...")
                packValid = false
            } else if (obbIsCorrupted(expansionPackPath, mainPackMd5)) {
                Log.e(TAG, "Expansion pack '$expansionPackPath' is corrupted...")
                packValid = false
                try {
                    f.delete()
                } catch (_: java.lang.Exception) {
                }
            }
            if (!packValid) {
                try {
                    val activity = getActivity()
                    val notifierIntent = Intent(activity, activity!!.javaClass)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    val pendingIntent = PendingIntent.getActivity(
                        activity,
                        0,
                        notifierIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val startResult = DownloaderClientMarshaller.startDownloadServiceIfRequired(
                        context,
                        pendingIntent,
                        GodotDownloaderService::class.java
                    )
                    if (startResult != DownloaderClientMarshaller.NO_DOWNLOAD_REQUIRED) {
                        // This is where you do set up to display the download
                        // progress (next step in onMainCreate)
                        mDownloaderClientStub = DownloaderClientMarshaller.CreateStub(
                            this,
                            GodotDownloaderService::class.java
                        )
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(TAG, "Unable to start download service", e)
                }
            }
        }

        return if (packValid && expansionPackPath.isNotEmpty()) {
            listOf("--main-pack", expansionPackPath)
        } else {
            Collections.emptyList()
        }
    }

    override fun onMainCreate(activity: Activity?): View? {
        if (mDownloaderClientStub == null) {
            return null
        }

        val downloadingExpansionView = activity?.layoutInflater?.inflate(R.layout.downloading_expansion, null)
        mPB = downloadingExpansionView?.findViewById(R.id.progressBar)
        mStatusText = downloadingExpansionView?.findViewById(R.id.statusText)
        mProgressFraction = downloadingExpansionView?.findViewById(R.id.progressAsFraction)
        mProgressPercent = downloadingExpansionView?.findViewById(R.id.progressAsPercentage)
        mAverageSpeed = downloadingExpansionView?.findViewById(R.id.progressAverageSpeed)
        mTimeRemaining = downloadingExpansionView?.findViewById(R.id.progressTimeRemaining)
        mDashboard = downloadingExpansionView?.findViewById(R.id.downloaderDashboard)
        mCellMessage = downloadingExpansionView?.findViewById(R.id.approveCellular)
        mPauseButton = downloadingExpansionView?.findViewById(R.id.pauseButton)

        return downloadingExpansionView
    }

    override fun onMainStart() {
        if (!godot.isInitialized()) {
            if (null != mDownloaderClientStub) {
                mDownloaderClientStub!!.connect(activity)
            }
            return
        }
    }

    override fun onMainResume() {
        if (!godot.isInitialized()) {
            if (null != mDownloaderClientStub) {
                mDownloaderClientStub!!.connect(activity)
            }
            return
        }
    }

    override fun onMainPause() {
        if (!godot.isInitialized()) {
            if (null != mDownloaderClientStub) {
                mDownloaderClientStub!!.disconnect(activity)
            }
            return
        }
    }

    override fun onMainStop() {
        if (!godot.isInitialized()) {
            if (null != mDownloaderClientStub) {
                mDownloaderClientStub!!.disconnect(activity)
            }
            return
        }
    }

    private fun setState(newState: Int) {
        if (mState != newState) {
            mState = newState
            mStatusText!!.setText(Helpers.getDownloaderStringResourceIDFromState(newState))
        }
    }

    private fun setButtonPausedState(paused: Boolean) {
        val stringResourceID =
            if (paused) R.string.text_button_resume else R.string.text_button_pause
        mPauseButton!!.setText(stringResourceID)
    }

    override fun onServiceConnected(m: Messenger?) {
        val remoteService = DownloaderServiceMarshaller.CreateProxy(m)
        remoteService.onClientUpdated(mDownloaderClientStub!!.getMessenger())
    }

    /**
     * The download state should trigger changes in the UI --- it may be useful
     * to show the state as being indeterminate at times. This sample can be
     * considered a guideline.
     */
    override fun onDownloadStateChanged(newState: Int) {
        setState(newState)
        var showDashboard = true
        var showCellMessage = false
        val paused: Boolean
        val indeterminate: Boolean
        when (newState) {
            IDownloaderClient.STATE_IDLE -> {
                // STATE_IDLE means the service is listening, so it's
                // safe to start making remote service calls.
                paused = false
                indeterminate = true
            }

            IDownloaderClient.STATE_CONNECTING, IDownloaderClient.STATE_FETCHING_URL -> {
                showDashboard = true
                paused = false
                indeterminate = true
            }

            IDownloaderClient.STATE_DOWNLOADING -> {
                paused = false
                showDashboard = true
                indeterminate = false
            }

            IDownloaderClient.STATE_FAILED_CANCELED, IDownloaderClient.STATE_FAILED, IDownloaderClient.STATE_FAILED_FETCHING_URL, IDownloaderClient.STATE_FAILED_UNLICENSED -> {
                paused = true
                showDashboard = false
                indeterminate = false
            }

            IDownloaderClient.STATE_PAUSED_NEED_CELLULAR_PERMISSION, IDownloaderClient.STATE_PAUSED_WIFI_DISABLED_NEED_CELLULAR_PERMISSION -> {
                showDashboard = false
                paused = true
                indeterminate = false
                showCellMessage = true
            }

            IDownloaderClient.STATE_PAUSED_BY_REQUEST -> {
                paused = true
                indeterminate = false
            }

            IDownloaderClient.STATE_PAUSED_ROAMING, IDownloaderClient.STATE_PAUSED_SDCARD_UNAVAILABLE -> {
                paused = true
                indeterminate = false
            }

            IDownloaderClient.STATE_COMPLETED -> {
                showDashboard = false
                paused = false
                indeterminate = false
                // Restart the engine.
                // TODO: Prompt the user before restarting.
                ProcessPhoenix.triggerRebirth(context)
                return
            }

            else -> {
                paused = true
                indeterminate = true
                showDashboard = true
            }
        }
        val newDashboardVisibility = if (showDashboard) View.VISIBLE else View.GONE
        if (mDashboard!!.getVisibility() != newDashboardVisibility) {
            mDashboard!!.setVisibility(newDashboardVisibility)
        }
        val cellMessageVisibility = if (showCellMessage) View.VISIBLE else View.GONE
        if (mCellMessage!!.getVisibility() != cellMessageVisibility) {
            mCellMessage!!.setVisibility(cellMessageVisibility)
        }

        mPB!!.setIndeterminate(indeterminate)
        setButtonPausedState(paused)
    }

    override fun onDownloadProgress(progress: DownloadProgressInfo) {
        mAverageSpeed?.setText(
            context.getString(
                R.string.kilobytes_per_second,
                Helpers.getSpeedString(progress.mCurrentSpeed)
            )
        )
        mTimeRemaining?.setText(
            context.getString(
                R.string.time_remaining,
                Helpers.getTimeRemaining(progress.mTimeRemaining)
            )
        )

        mPB!!.setMax((progress.mOverallTotal shr 8).toInt())
        mPB!!.setProgress((progress.mOverallProgress shr 8).toInt())
        mProgressPercent!!.setText(
            String.format(
                Locale.ENGLISH,
                "%d %%",
                progress.mOverallProgress * 100 / progress.mOverallTotal
            )
        )
        mProgressFraction!!.setText(
            Helpers.getDownloadProgressString(
                progress.mOverallProgress,
                progress.mOverallTotal
            )
        )
    }

    private fun obbIsCorrupted(f: String, mainPackMd5: String): Boolean {
        return try {
            val fis: InputStream = FileInputStream(f)

            // Create MD5 Hash
            val buffer = ByteArray(16384)
            val complete = MessageDigest.getInstance("MD5")
            var numRead: Int
            do {
                numRead = fis.read(buffer)
                if (numRead > 0) {
                    complete.update(buffer, 0, numRead)
                }
            } while (numRead != -1)
            fis.close()
            val messageDigest = complete.digest()

            // Create Hex String
            val hexString = StringBuilder()
            for (b in messageDigest) {
                var s = Integer.toHexString(0xFF and b.toInt())
                if (s.length == 1) {
                    s = "0$s"
                }
                hexString.append(s)
            }
            val md5str = hexString.toString()
            md5str != mainPackMd5
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            true
        }
    }

    private fun parseAssetsCommandLine(): MutableList<String> {
        val commandLines = mutableListOf<String>()
        try {
            activity?.assets?.open(COMMAND_LINES_FILE_NAME)?.bufferedReader()?.use { reader ->
                val content = reader.readText()
                if (content.isNotEmpty()) {
                    commandLines.addAll(content.split(COMMAND_LINES_ARG_SEPARATOR))
                }
            }
        } catch (_: Exception) {
        }
        return commandLines
    }
}
