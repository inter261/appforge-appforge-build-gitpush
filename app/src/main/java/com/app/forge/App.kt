package com.app.forge

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.app.forge.data.HistoryStore
import com.app.forge.data.Store

class App : Application() {
    lateinit var store: Store
        private set
    lateinit var hist: HistoryStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        hist = HistoryStore(this)
        AppCompatDelegate.setDefaultNightMode(
            if (store.darkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}

val Context.app: App get() = applicationContext as App
