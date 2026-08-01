package cn.ppps.forwarder.core

import android.app.Application
import androidx.work.Configuration
import cn.ppps.forwarder.App
import cn.ppps.forwarder.BuildConfig
import cn.ppps.forwarder.utils.Log
import kotlinx.coroutines.launch

object Core : Configuration.Provider {
    lateinit var app: Application

    fun init(app: Application) {
        this.app = app
    }

    override fun getWorkManagerConfiguration(): Configuration {
        return Configuration.Builder().apply {
            setDefaultProcessName(app.packageName + ":bg")
            setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.VERBOSE else Log.INFO)
            setExecutor { (app as App).applicationScope.launch { it.run() } }
            setTaskExecutor { (app as App).applicationScope.launch { it.run() } }
        }.build()
    }
}
