package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import waveshare.feng.nfctag.activity.a
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class NfcFlasher : AppCompatActivity(), NfcAdapter.ReaderCallback {
    companion object {
        private const val TAG = "NfcFlasher"
        
        private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or 
                                         NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                                         NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        
        private const val PRESENCE_CHECK_DELAY_MS = 30000
    }
    
    private val mIsFlashing = AtomicBoolean(false)
    
    private var mNfcAdapter: NfcAdapter? = null
    private var mProgressBar: ProgressBar? = null
    private var mBitmap: Bitmap? = null
    private var mWhileFlashingArea: ConstraintLayout? = null
    private var mImgFilePath: String? = null
    private var mImgFileUri: Uri? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (mImgFileUri != null) {
            outState.putString("serializedGeneratedImgUri", mImgFileUri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_flasher)

        val savedUriStr = savedInstanceState?.getString("serializedGeneratedImgUri")
        if (savedUriStr != null) {
            mImgFileUri = Uri.parse(savedUriStr)
        } else {
            val intentExtras = intent.extras
            mImgFilePath = intentExtras?.getString(IntentKeys.GeneratedImgPath)
            if (mImgFilePath != null) {
                val fileRef = getFileStreamPath(mImgFilePath)
                mImgFileUri = Uri.fromFile(fileRef)
            }
        }
        if (mImgFileUri == null) {
            val fileRef = getFileStreamPath(GeneratedImageFilename)
            mImgFileUri = Uri.fromFile(fileRef)
        }

        val imagePreviewElem: ImageView = findViewById(R.id.previewImageView)
        imagePreviewElem.setImageURI(mImgFileUri)

        if (mImgFileUri != null) {
            val bmOptions = BitmapFactory.Options()
            this.mBitmap = BitmapFactory.decodeFile(mImgFileUri!!.path, bmOptions)
        }

        mWhileFlashingArea = findViewById(R.id.whileFlashingArea)
        mProgressBar = findViewById(R.id.nfcFlashProgressbar)

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (mNfcAdapter == null) {
            Toast.makeText(this, "NFC is not available on this device.", Toast.LENGTH_LONG).show()
        } else {
            Log.i(TAG, "NFC adapter initialized")
        }
    }

    override fun onResume() {
        super.onResume()
        enableReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }

    private fun enableReaderMode() {
        val adapter = mNfcAdapter ?: return
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS)
        }
        adapter.enableReaderMode(this, this, READER_FLAGS, options)
        Log.i(TAG, "Reader mode enabled: NFC_A + SKIP_NDEF + NO_SOUNDS, presenceCheckDelay=${PRESENCE_CHECK_DELAY_MS}ms")
    }

    private fun disableReaderMode() {
        mNfcAdapter?.disableReaderMode(this)
        Log.i(TAG, "Reader mode disabled")
    }

    override fun onTagDiscovered(tag: Tag) {
        Log.i(TAG, "onTagDiscovered: tag=$tag, tech=[${tag.techList.joinToString()}]")

        val bitmap = this.mBitmap
        if (bitmap == null) {
            Log.e(TAG, "No bitmap to flash!")
            runOnUiThread {
                Toast.makeText(this, "No image to flash!", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val preferences = Preferences(this)
        val screenSizeEnum = preferences.getScreenSizeEnum()
        val requiredTechnology = if (screenSizeEnum == 8) IsoDep::class.java.name else NfcA::class.java.name
        if (!tag.techList.contains(requiredTechnology)) {
            Log.e(TAG, "Tag doesn't support $requiredTechnology")
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Tag lacks ${requiredTechnology.substringAfterLast('.')}: ${tag.techList.joinToString()}",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        if (!mIsFlashing.compareAndSet(false, true)) {
            Log.w(TAG, "Already flashing, ignoring tag")
            return
        }
        updateFlashingUi(true)
        flashBitmap(tag, bitmap, screenSizeEnum)
    }

    private fun flashBitmap(tag: Tag, bitmap: Bitmap, screenSizeEnum: Int) {
        var errorString = ""

        Thread({
            var success = false

            Log.i(TAG, "flashBitmap: screenSizeEnum=$screenSizeEnum, bitmap=${bitmap.width}x${bitmap.height}, tech=[${tag.techList.joinToString()}]")

            try {
                if (screenSizeEnum == 8) {
                    Log.i(TAG, "Using native 1.54\" B/W/R ISO-DEP protocol")
                    val protocol = WaveShare154BProtocol(tag) { progress ->
                        runOnUiThread { updateProgressBar(progress) }
                    }
                    success = protocol.flash(bitmap)
                    if (!success) {
                        errorString = "Native 1.54B protocol failed - see WS154B logcat"
                    }
                } else {
                    val nfcTag = NfcA.get(tag)
                    Log.i(TAG, "Using WaveShare SDK for non-1.54B screen")
                    nfcTag.connect()
                    nfcTag.timeout = 5000
                    
                        val sdk = a()
                        val connectionResult = sdk.a(nfcTag)
                        if (connectionResult != 1) {
                            errorString = "Failed to connect to NFC tag"
                            return@Thread
                        }

                        try {
                            val nfcAField = sdk.javaClass.getDeclaredField("k")
                            nfcAField.isAccessible = true
                            val sdkNfcA = nfcAField.get(sdk) as? NfcA
                            sdkNfcA?.timeout = 5000
                            Log.i(TAG, "Set SDK internal NfcA timeout to 5000ms")
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not set SDK timeout via reflection: ${e.message}")
                        }

                        val stopPolling = AtomicBoolean(false)
                        val progressThread = Thread({
                            try {
                                var progress = 0
                                while (!stopPolling.get() && progress != -1 && progress < 100) {
                                    progress = sdk.c
                                    runOnUiThread { updateProgressBar(progress) }
                                    SystemClock.sleep(50)
                                }
                            } catch (error: Exception) {
                                if (!stopPolling.get()) {
                                    Log.w(TAG, "Progress polling failed", error)
                                }
                            }
                        }, "nfc-progress")
                        progressThread.start()

                        try {
                            val result = sdk.a(screenSizeEnum, bitmap)
                            if (result == 1) {
                                success = true
                            } else if (result == 2) {
                                errorString = "Incorrect image resolution"
                            } else {
                                errorString = "Flash failed (code: $result)"
                            }
                        } finally {
                            stopPolling.set(true)
                            progressThread.interrupt()
                            try {
                                progressThread.join(200)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            try {
                                nfcTag.close()
                            } catch (error: IOException) {
                                Log.e(TAG, "Error closing tag", error)
                            }
                        }
                }
            } catch (error: Exception) {
                errorString = error.toString()
                Log.e(TAG, "Flash error", error)
            } finally {
                mIsFlashing.set(false)
                updateFlashingUi(false)
                runOnUiThread {
                    val message = if (success) "Success! Flashed display!" else "FAILED to Flash :( $errorString"
                    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                }
                Log.i(TAG, "Flash complete. Success = $success")
            }
        }, "nfc-flash").start()
    }

    private fun updateFlashingUi(isFlashing: Boolean) {
        runOnUiThread {
            mWhileFlashingArea?.visibility = if (isFlashing) android.view.View.VISIBLE else android.view.View.GONE
            mWhileFlashingArea?.requestLayout()
            mProgressBar?.progress = 0
        }
    }

    private fun updateProgressBar(updated: Int) {
        if (mProgressBar == null) {
            mProgressBar = findViewById(R.id.nfcFlashProgressbar)
        }
        mProgressBar?.setProgress(updated, true)
    }
}
