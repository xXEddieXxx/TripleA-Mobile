package org.triplea.mobile.app.forum

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.triplea.mobile.app.AppServices
import org.triplea.mobile.forum.Forum
import org.triplea.mobile.forum.Http
import org.triplea.mobile.forum.NodeBbClient

/**
 * The player's session on each [Forum]: the forum's `express.sid` cookie from the in-app login,
 * kept AES-GCM encrypted with an Android Keystore key. Never logged. Also which forum the Play by
 * Forum screen shows.
 */
object ForumAccount {
    private const val TAG = "ForumAccount"
    private const val PREFS = "forum"
    private const val KEY_ALIAS = "triplea-forum-session"
    private const val GCM_TAG_BITS = 128

    private lateinit var prefs: SharedPreferences

    /**
     * The forums the app offers. www.axisandallies.org stays out until its Cloudflare protection lets
     * the app's requests through (it answers each with a challenge); its support is kept, add it back here.
     */
    val shown: List<Forum> = listOf(Forum.TRIPLEA)

    private val sessions = java.util.concurrent.ConcurrentHashMap<Forum, String>()
    private val players = Forum.entries.associateWith { MutableStateFlow<NodeBbClient.Player?>(null) }
    private val _selected = MutableStateFlow(Forum.TRIPLEA)

    /** The forum the Play by Forum screen shows. */
    val selected: StateFlow<Forum> = _selected.asStateFlow()

    /** The signed-in player of the forum, null while signed out or not yet verified. */
    fun player(forum: Forum): StateFlow<NodeBbClient.Player?> = players.getValue(forum).asStateFlow()

    fun hasSession(forum: Forum): Boolean = sessions.containsKey(forum)

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Forum.entries.forEach { forum ->
            prefs.getString(sessionKey(forum), null)
                ?.let { stored -> runCatching { decrypt(stored) }.onFailure { Log.w(TAG, "Stored $forum session unreadable") }.getOrNull() }
                ?.let { sessions[forum] = it }
        }
        _selected.value = prefs.getString("selected", null)?.let { name -> shown.firstOrNull { it.name == name } } ?: shown.first()
        if (sessions.isNotEmpty()) ForumTurnJob.schedule(context)
    }

    fun select(forum: Forum) {
        _selected.value = forum
        prefs.edit { putString("selected", forum.name) }
    }

    /** A client of the forum signed in with its session, null when signed out there. */
    fun client(forum: Forum): NodeBbClient? =
        sessions[forum]?.let { NodeBbClient(Http.real(false), forum, it) }

    /**
     * Asks the forum whose the session is. A session that ran out signs out; a network error
     * keeps it and is thrown, so being offline does not lose the login.
     */
    suspend fun verify(forum: Forum): NodeBbClient.Player? = withContext(Dispatchers.IO) {
        val client = client(forum) ?: return@withContext null
        val player = client.signedInPlayer().orElse(null)
        if (player == null) signOut(forum) else players.getValue(forum).value = player
        player
    }

    /** Verifies the cookie of the forum's login page and keeps it when the forum signs someone in. */
    suspend fun signIn(forum: Forum, cookie: String): NodeBbClient.Player? = withContext(Dispatchers.IO) {
        val player = NodeBbClient(Http.real(false), forum, cookie)
            .signedInPlayer().orElse(null) ?: return@withContext null
        sessions[forum] = cookie
        prefs.edit { putString(sessionKey(forum), encrypt(cookie)) }
        players.getValue(forum).value = player
        ForumTurnJob.schedule(AppServices.appContext)
        player
    }

    fun signOut(forum: Forum) {
        sessions.remove(forum)
        players.getValue(forum).value = null
        prefs.edit { remove(sessionKey(forum)) }
        if (sessions.isEmpty()) ForumTurnJob.cancel(AppServices.appContext)
        // the web login's cookies of both forums go; the other forum's session is kept by the app
        CookieManager.getInstance().removeAllCookies(null)
    }

    /** The TripleA forum keeps the key it had before there were two forums. */
    private fun sessionKey(forum: Forum) = if (forum == Forum.TRIPLEA) "session" else "session.${forum.name}"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // GCM's IV is 12 bytes
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, sealed, 0, 12))
        return String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8)
    }
}
