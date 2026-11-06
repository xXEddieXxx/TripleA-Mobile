package org.triplea.mobile.forum;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PbfPostTest {
  @Test
  void titleIsReadBackAsTheTurn() {
    final String title = PbfPost.title("Turn Summary", "Germans", 3);

    assertThat(title).isEqualTo("TripleA Turn Summary: Germans round 3");
    assertThat(NodeBbClient.turnOf("<h3>" + title + "</h3>")).isEqualTo("Germans, round 3");
  }

  @Test
  void saveNameKeepsDesktopsShortening() {
    assertThat(PbfPost.saveName(1234, 3, "Germans")).isEqualTo("triplea_1234_3Ger.tsvg");
    assertThat(PbfPost.saveName(1234, 3, "UK")).isEqualTo("triplea_1234_3U.tsvg");
  }

  @Test
  void contentHasHeadingGameMessageSummaryAndSave() {
    final String content =
        PbfPost.content(
            "Turn Summary",
            "Germans",
            3,
            "World War II Classic",
            "  Good luck!  ",
            "Round 3\n  Germans buy 1 infantry",
            "/assets/uploads/files/x.tsvg");

    assertThat(content)
        .isEqualTo(
            "### TripleA Turn Summary: Germans round 3\n"
                + "TripleA Turn Summary for game: World War II Classic\n\n"
                + "Good luck!\n\n"
                + "Round 3\n  Germans buy 1 infantry"
                + "\n\n[Savegame](/assets/uploads/files/x.tsvg)");
  }

  @Test
  void contentWithoutMessageHasNoEmptyParagraph() {
    assertThat(PbfPost.content("Turn Summary", "UK", 1, "G", " ", "S", "/u.tsvg"))
        .isEqualTo(
            "### TripleA Turn Summary: UK round 1\nTripleA Turn Summary for game: G\n\n"
                + "S\n\n[Savegame](/u.tsvg)");
  }
}
