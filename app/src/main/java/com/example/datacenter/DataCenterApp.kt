package com.example.datacenter

import android.app.Application

class DataCenterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Info.iniciar(this)
    }
}
