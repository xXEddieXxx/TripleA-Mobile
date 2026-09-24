package org.triplea.mobile.app

import android.app.Application
import android.content.Context
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.triplea.mobile.MobileEngine

class TripleAApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServices.init(this)
    }
}

/** Process wide services: engine configuration and bundled content installation. */
object AppServices {
    lateinit var appContext: Context
        private set

    val rootDir: Path get() = appContext.filesDir.toPath().resolve("triplea")
    val engineAssetsDir: Path get() = rootDir.resolve("engineAssets")

    @Volatile
    var contentInstalled: Boolean = false
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        // Android has no built-in StAX implementation; point the factories at Woodstox explicitly.
        System.setProperty("javax.xml.stream.XMLInputFactory", "com.ctc.wstx.stax.WstxInputFactory")
        System.setProperty("javax.xml.stream.XMLOutputFactory", "com.ctc.wstx.stax.WstxOutputFactory")
        System.setProperty("javax.xml.stream.XMLEventFactory", "com.ctc.wstx.stax.WstxEventFactory")
        MobileEngine.configure(rootDir)
        MobileEngine.setEngineAssetsFolder(engineAssetsDir)
        AppSettings.init(appContext)
    }

    /** Unpacks the bundled maps and engine images on first start. Safe to call repeatedly. */
    suspend fun installBundledContent() {
        if (contentInstalled) return
        withContext(Dispatchers.IO) {
            MapInstaller.installAll(appContext, MobileEngine.getMapsFolder(), engineAssetsDir)
            contentInstalled = true
        }
    }
}
