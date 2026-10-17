package io.github.yunuserdemsihhatli.homecast

import android.app.Application

class HomeCastApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
