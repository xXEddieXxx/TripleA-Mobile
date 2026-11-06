package org.triplea.mobile.forum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.apache.commons.text.StringEscapeUtils;

/**
 * The Markdown the editor's toolbar writes as HTML, for the preview before sending: paragraphs,
 * quotes, lists, bold, italic, links and pictures. The forum renders the real post itself.
 */
@UtilityClass
public class ForumMarkdown {
  private static final Pattern PICTURE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)");
  private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
  private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
  private static final Pattern ITALIC = Pattern.compile("\\*(.+?)\\*");

  public static String toHtml(final String markdown) {
    final StringBuilder html = new StringBuilder();
    final List<String> paragraph = new ArrayList<>();
    String block = null;
    for (final String line : (markdown + "\n").split("\n", -1)) {
      final String kind = kindOf(line);
      if (!Objects.equals(kind, block)) {
        close(html, block, paragraph);
        block = kind;
      }
      if (kind != null) {
        paragraph.add(line.substring("p".equals(kind) ? 0 : Math.min(2, line.length())));
      }
    }
    return html.toString();
  }

  /** The block a line belongs to; null for a blank line, which ends a paragraph. */
  private static String kindOf(final String line) {
    if (line.startsWith("> ") || line.equals(">")) {
      return "blockquote";
    }
    if (line.startsWith("- ")) {
      return "ul";
    }
    return line.isBlank() ? null : "p";
  }

  private static void close(final StringBuilder html, final String block, final List<String> lines) {
    if (block == null || lines.isEmpty()) {
      lines.clear();
      return;
    }
    if ("ul".equals(block)) {
      html.append("<ul>");
      lines.forEach(line -> html.append("<li>").append(inline(line)).append("</li>"));
      html.append("</ul>");
    } else {
      final String text = String.join("<br>", lines.stream().map(ForumMarkdown::inline).toList());
      html.append("blockquote".equals(block) ? "<blockquote><p>" + text + "</p></blockquote>" : "<p>" + text + "</p>");
    }
    lines.clear();
  }

  /** One line: escaped first, so the player's text never becomes markup of its own. */
  private static String inline(final String line) {
    String html = StringEscapeUtils.escapeHtml4(line);
    html = PICTURE.matcher(html).replaceAll("<img src=\"$2\" alt=\"$1\">");
    html = LINK.matcher(html).replaceAll("<a href=\"$2\">$1</a>");
    html = BOLD.matcher(html).replaceAll("<b>$1</b>");
    return ITALIC.matcher(html).replaceAll("<i>$1</i>");
  }
}
