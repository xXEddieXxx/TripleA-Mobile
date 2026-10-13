package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import java.nio.file.Files
import java.nio.file.Path
import org.triplea.mobile.MobileEngine

/** The engine tests' minimap, installed once per test JVM into a temp user folder. */
internal object Minimap {
    private val installed by lazy {
        MobileEngine.configure(Files.createTempDirectory("online-test"))
        val source = Path.of(Minimap::class.java.getResource("/maps/minimap")!!.toURI())
        val target = MobileEngine.getMapsFolder().resolve("minimap")
        Files.walk(source).use { paths ->
            for (path in paths.toList()) {
                val dest = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(dest) else Files.copy(path, dest)
            }
        }
    }

    fun parse(): GameData {
        installed
        return MobileEngine.parseGame(MobileEngine.listInstalledGames()[0].xmlPath).orElseThrow()
    }
}
