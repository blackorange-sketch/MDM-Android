package dev.blackorange.mdm

import android.app.Application

class MdmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Native.start(this)
    }
}
