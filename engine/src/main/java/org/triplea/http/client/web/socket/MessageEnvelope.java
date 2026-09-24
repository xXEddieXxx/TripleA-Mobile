package org.triplea.http.client.web.socket;

import lombok.Getter;
import org.triplea.http.client.web.socket.messages.MessageType;
import org.triplea.http.client.web.socket.messages.WebSocketMessage;

/** Stub of the desktop websocket envelope, see {@link WebSocketMessage}. Never transmitted. */
@Getter
public class MessageEnvelope {
  private final String messageTypeId;
  private final WebSocketMessage payload;

  private MessageEnvelope(final String messageTypeId, final WebSocketMessage payload) {
    this.messageTypeId = messageTypeId;
    this.payload = payload;
  }

  public static <T extends WebSocketMessage> MessageEnvelope packageMessage(
      final MessageType<T> messageType, final T data) {
    return new MessageEnvelope(messageType.getMessageTypeId(), data);
  }

  @SuppressWarnings("unchecked")
  public <T> T getPayload(final Class<T> type) {
    return (T) payload;
  }

  public boolean messageTypeIs(final MessageType<?> messageType) {
    return messageTypeId.equals(messageType.getMessageTypeId());
  }
}
