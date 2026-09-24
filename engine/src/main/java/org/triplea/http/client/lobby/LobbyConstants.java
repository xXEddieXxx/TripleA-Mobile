package org.triplea.http.client.lobby;

import lombok.experimental.UtilityClass;

/** Validation constants shared with the desktop lobby client data model. */
@UtilityClass
public class LobbyConstants {
  public static final int USERNAME_MIN_LENGTH = 3;
  public static final int USERNAME_MAX_LENGTH = 40;
  public static final int PASSWORD_MIN_LENGTH = 3;
  public static final int EMAIL_MAX_LENGTH = 254;
}
