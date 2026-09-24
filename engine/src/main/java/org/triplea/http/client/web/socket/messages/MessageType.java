package org.triplea.http.client.web.socket.messages;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/** Stub of the desktop websocket message type descriptor, see {@link WebSocketMessage}. */
@EqualsAndHashCode
public class MessageType<T extends WebSocketMessage> {
  @Getter private final Class<T> payloadType;

  private MessageType(final Class<T> payloadType) {
    this.payloadType = payloadType;
  }

  public static <X extends WebSocketMessage> MessageType<X> of(final Class<X> classType) {
    return new MessageType<>(classType);
  }

  public String getMessageTypeId() {
    return payloadType.getSimpleName();
  }
}
