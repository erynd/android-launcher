package com.geode.launcher

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.customRobTop.BaseRobTopActivity
import com.customRobTop.JniToCpp
import com.geode.launcher.utils.Constants
import com.geode.launcher.utils.LaunchUtils
import org.cocos2dx.lib.Cocos2dxEditText
import org.cocos2dx.lib.Cocos2dxGLSurfaceView
import org.cocos2dx.lib.Cocos2dxHelper
import org.cocos2dx.lib.Cocos2dxRenderer
import org.fmod.FMOD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream


class GeometryDashActivity : AppCompatActivity(), Cocos2dxHelper.Cocos2dxHelperListener {
    private var mGLSurfaceView: Cocos2dxGLSurfaceView? = null
    private val sTag = GeometryDashActivity::class.simpleName
    private var mIsRunning = false
    private var mIsOnPause = false
    private var mHasWindowFocus = false
    private var mReceiver: BroadcastReceiver? = null

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun onCreate(savedInstanceState: Bundle?) {
        setupUIState()

        // return back to main if Geometry Dash isn't found
        if (!LaunchUtils.isGeometryDashInstalled(packageManager)) {
            val launchIntent = Intent(this, MainActivity::class.java)
            launchIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK

            startActivity(launchIntent)
        }

        val gdPackageInfo = packageManager.getPackageInfo(Constants.PACKAGE_NAME, 0)
        val gdNativeLibraryPath = "${gdPackageInfo.applicationInfo.nativeLibraryDir}/"

        try {
            LaunchUtils.addAssetsFromPackage(assets, gdPackageInfo)
        } catch (e: NoSuchMethodException) {
            e.printStackTrace()
        }

        try {
            // fixes bugs specific to the new app directory, such as package name
            LauncherFix.loadLibrary()
            LauncherFix.setOriginalDataPath(Constants.GJ_DATA_DIR)
            LauncherFix.setDataPath(filesDir.path)
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }

        Cocos2dxHelper.init(this, this)

        System.load("$gdNativeLibraryPath/lib${Constants.FMOD_LIB_NAME}.so")
        System.load("$gdNativeLibraryPath/lib${Constants.COCOS_LIB_NAME}.so")

        if (getLoadTesting()) {
            loadTestingLibraries()
        }

        FMOD.init(this)

        super.onCreate(savedInstanceState)

        setContentView(createView())

        // call native functions after native libraries init
        JniToCpp.setupHSSAssets(
            gdPackageInfo.applicationInfo.sourceDir,
            Environment.getExternalStorageDirectory().absolutePath
        )
        Cocos2dxHelper.nativeSetApkPath(gdPackageInfo.applicationInfo.sourceDir)

        BaseRobTopActivity.setCurrentActivity(this)
        registerReceiver()

        if (!loadGeodeLibrary()) {
            Log.w("GeodeLauncher", "could not load Geode object!")
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadGeodeLibrary(): Boolean {
        // Load Geode if exists
        // bundling the object with the application allows for nicer backtraces
        try {
            // put libgeode.so in jniLibs/armeabi-v7a to get this
            System.loadLibrary("geode")
            return true
        } catch (e: UnsatisfiedLinkError) {
            // but users may prefer it stored with data
            val geodePath = File(filesDir.path, "launcher/Geode.so")
            if (geodePath.exists()) {
                System.load(geodePath.path)
                return true
            }
            else {
                // you know zmx i have 0 clue what this does so im 
                // just gonna like copy the binary from external
                // also i get 20 million permission denied errors
                getExternalFilesDir(null)?.let { dir ->
                    val externalGeodePath = File(dir.path, "Geode.so")

                    val copiedPath = File(filesDir.path, "copied")
                    if (copiedPath.exists()) {
                        copiedPath.deleteRecursively()
                    }
                    copiedPath.mkdir()

                    val geodePath = File(copiedPath.path, "Geode.so")

                    if (externalGeodePath.exists()) {
                        copyFile(FileInputStream(externalGeodePath), FileOutputStream(geodePath))

                        if (geodePath.exists()) {
                            try {
                                println("Loading Geode from ${externalGeodePath.name}")
                                System.load(geodePath.path)
                            } catch (e: UnsatisfiedLinkError) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }

        return false
    }

    private fun createView(): FrameLayout {
        val frameLayoutParams = ViewGroup.LayoutParams(-1, -1)
        val frameLayout = FrameLayout(this)
        frameLayout.layoutParams = frameLayoutParams
        val editTextLayoutParams = ViewGroup.LayoutParams(-1, -2)
        val editText = Cocos2dxEditText(this)
        editText.layoutParams = editTextLayoutParams
        frameLayout.addView(editText)

        val glSurfaceView = Cocos2dxGLSurfaceView(this)

        this.mGLSurfaceView = glSurfaceView
        frameLayout.addView(this.mGLSurfaceView)

        glSurfaceView.setEGLConfigChooser(5, 6, 5, 0, 16, 8)

        if (isAndroidEmulator()) {
            glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        }

        glSurfaceView.initView()
        glSurfaceView.setCocos2dxRenderer(Cocos2dxRenderer())

        editText.inputType = 145
        glSurfaceView.cocos2dxEditText = editText

        return frameLayout
    }

    private fun setupUIState() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE

        hideSystemUi()
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // window compat is dumb!!
            setLegacyVisibility()
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    setLegacyVisibility()
                }
            }
        }

        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setLegacyVisibility() {
        // setDecorFitsSystemWindows doesn't hide anything
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceivers()
        FMOD.close()
    }

    private fun resumeGame() {
        mIsRunning = true
//        Cocos2dxHelper.onResume()
        mGLSurfaceView?.onResume()
    }

    private fun pauseGame() {
        mIsRunning = false
//        Cocos2dxHelper.onPause()
        mGLSurfaceView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        mIsOnPause = false
        BaseRobTopActivity.isPaused = false
        if (mHasWindowFocus && !this.mIsRunning) {
            resumeGame()
        }
    }

    override fun onPause() {
        super.onPause()
        mIsOnPause = true
        BaseRobTopActivity.isPaused = true
        if (mIsRunning) {
            pauseGame()
        }
    }

    private fun registerReceiver() {
        unregisterReceivers()
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            mReceiver = BaseRobTopActivity.ReceiverScreen()
            registerReceiver(mReceiver, filter)
        } catch (_: Exception) {
        }
    }

    private fun unregisterReceivers() {
        if (mReceiver != null) {
            unregisterReceiver(mReceiver)
            mReceiver = null
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        mHasWindowFocus = hasWindowFocus
        if (hasWindowFocus && !mIsOnPause) {
            resumeGame()
        }
    }

    override fun runOnGLThread(runnable: Runnable) {
        mGLSurfaceView?.queueEvent(runnable)
    }

    override fun showDialog(title: String, message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                // the button shouldn't do anything but close
                .setPositiveButton(R.string.message_box_accept) { _, _ -> }
                .show()
        }
    }

    override fun showEditTextDialog(
        title: String,
        message: String,
        inputMode: Int,
        inputFlag: Int,
        returnType: Int,
        maxLength: Int
    ) {
        TODO("Not yet implemented")
    }

    private fun isAndroidEmulator(): Boolean {
        Log.d(sTag, "model=" + Build.MODEL)
        val product = Build.PRODUCT
        Log.d(sTag, "product=$product")
        var isEmulator = false
        if (product != null) {
            isEmulator = product == "sdk" || product.contains("_sdk") || product.contains("sdk_")
        }
        Log.d(sTag, "isEmulator=$isEmulator")
        return isEmulator
    }

    private fun getLoadTesting(): Boolean {
        val preferences = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)

        return preferences.getBoolean(getString(R.string.preference_load_testing), false)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadTestingLibraries() {
        // clear data dir
        val testDirPath = File(filesDir.path + File.separator + "testlib" + File.separator)
        if (testDirPath.exists()) {
            testDirPath.deleteRecursively()
        }
        testDirPath.mkdir()

        getExternalFilesDir(null)?.let { dir ->
            val testingPath = dir.path + File.separator + "test" + File.separator

            File(testingPath).walk().forEach {
                if (it.isFile) {
                    // welcome to the world of Android classloader permissions
                    val outputFile = File(testDirPath.path + File.separator + it.name)
                    copyFile(FileInputStream(it), FileOutputStream(outputFile))

                    try {
                        println("Loading test library ${outputFile.name}")
                        System.load(outputFile.path)
                    } catch (e: UnsatisfiedLinkError) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun copyFile(inputStream: InputStream, outputStream: OutputStream) {
        // gotta love copying
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            FileUtils.copy(inputStream, outputStream)
        } else {
            inputStream.use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(4 * 1024)
                    while (true) {
                        val byteCount = input.read(buffer)
                        if (byteCount < 0) break
                        output.write(buffer, 0, byteCount)
                    }
                    output.flush()
                }
            }
        }
    }
}