package org.triplea.mobile.forum;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ForumMarkdownTest {
  @Test
  void toolbarMarkupBecomesHtml() {
    assertThat(
            ForumMarkdown.toHtml(
                "**Bold** and *it*\nnext line\n\n> quoted\n> more\n- one\n- two\n[site](https://a.org) ![](/assets/x.png)"))
        .isEqualTo(
            "<p><b>Bold</b> and <i>it</i><br>next line</p>"
                + "<blockquote><p>quoted<br>more</p></blockquote>"
                + "<ul><li>one</li><li>two</li></ul>"
                + "<p><a href=\"https://a.org\">site</a> <img src=\"/assets/x.png\" alt=\"\"></p>");
  }

  @Test
  void playersHtmlStaysText() {
    assertThat(ForumMarkdown.toHtml("<script>x</script>")).isEqualTo("<p>&lt;script&gt;x&lt;/script&gt;</p>");
  }

  @Test
  void emptyTextIsNoHtml() {
    assertThat(ForumMarkdown.toHtml("")).isEmpty();
  }
}
