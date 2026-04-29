package com.exifiler.android

import android.app.Application
import com.exifiler.AppContextHolder

class EXIFilerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.appContext = applicationContext
    }
}
