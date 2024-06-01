package com.erynd.launcher

import androidx.annotation.Keep
import com.erynd.launcher.utils.Constants

@Keep
object LauncherFix {
    fun loadLibrary() {
        System.loadLibrary(Constants.LAUNCHER_FIX_LIB_NAME)
    }

    external fun setDataPath(dataPath: String)

    external fun setOriginalDataPath(dataPath: String)
}