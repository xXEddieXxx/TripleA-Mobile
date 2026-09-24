package org.triplea.http.client.web.socket.messages;

import org.triplea.http.client.web.socket.MessageEnvelope;

/**
 * Kept as a compile-time compatible stub of the desktop websocket message interface. The mobile
 * engine never sends these messages anywhere; the local game uses direct in-process calls.
 */
public interface WebSocketMessage {
  MessageEnvelope toEnvelope();
}
