package com.m9r1u5.signalfinder

import android.app.Application
import com.m9r1u5.signalfinder.data.AppDatabase
import com.m9r1u5.signalfinder.map.MapSetup

class App : Application() {
    val db: AppDatabase by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        MapSetup.init(this)
    }
}
