package org.triplea.mobile.forum;

import lombok.experimental.UtilityClass;

/**
 * Play-by-forum settings stored in a save's game properties, under desktop TripleA's names
 * ({@code IForumPoster}, {@code IRemoteDiceServer}) so desktop and mobile saves read each other.
 */
@UtilityClass
public class PbfProperties {
  public static final String FORUM_NAME = "FORUM_POSTER_NAME";
  public static final String TOPIC_ID = "FORUM_POSTER_TOPIC_ID";
  public static final String DICE_EMAIL_1 = "DICE_SERVER_EMAIL_1";
  public static final String DICE_EMAIL_2 = "DICE_SERVER_EMAIL_2";
}
