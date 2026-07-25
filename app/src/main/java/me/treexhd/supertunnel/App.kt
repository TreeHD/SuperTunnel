package me.treexhd.supertunnel

import android.app.Application
import me.treexhd.supertunnel.data.room.ProfileRepository
import me.treexhd.supertunnel.data.secrets.SecretStore

class App : Application() {
    val profiles by lazy { ProfileRepository(this) }
    val secrets by lazy { SecretStore(this) }
}
