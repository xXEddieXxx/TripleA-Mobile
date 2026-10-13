package org.triplea.mobile.online.client

import org.triplea.mobile.protocol.TokensDto

/** Where tokens are kept; the app stores them in private SharedPreferences. */
interface TokenStore {
    var tokens: TokensDto?

    fun clear()
}

class MemoryTokenStore : TokenStore {
    @Volatile override var tokens: TokensDto? = null

    override fun clear() {
        tokens = null
    }
}
