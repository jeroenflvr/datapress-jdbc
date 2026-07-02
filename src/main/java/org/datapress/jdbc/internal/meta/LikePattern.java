package org.datapress.jdbc.internal.meta;

import java.util.regex.Pattern;

/**
 * SQL {@code LIKE} pattern matching used by {@code DatabaseMetaData} table- and column-name
 * filters. Supports the wildcards {@code %} (any run, including empty) and {@code _} (exactly one
 * character), plus an escape character that makes the following {@code %}, {@code _}, or escape
 * character literal.
 *
 * <p>Matching is case-sensitive. A {@code null} pattern matches everything (the JDBC "no filter"
 * convention); an empty pattern matches only the empty string. All other characters — including
 * regex metacharacters that may appear in dataset names — are matched literally.
 */
public final class LikePattern {

  /** Default escape character, matching {@code DatabaseMetaData.getSearchStringEscape()}. */
  public static final char DEFAULT_ESCAPE = '\\';

  /** {@code null} means "match everything" (the pattern was {@code null}). */
  private final Pattern regex;

  private LikePattern(Pattern regex) {
    this.regex = regex;
  }

  /** Compiles a pattern using the {@link #DEFAULT_ESCAPE default escape character}. */
  public static LikePattern compile(String pattern) {
    return compile(pattern, DEFAULT_ESCAPE);
  }

  /**
   * Compiles a {@code LIKE} pattern.
   *
   * @param pattern the pattern; {@code null} matches everything
   * @param escape the escape character; {@code '\0'} disables escaping
   */
  public static LikePattern compile(String pattern, char escape) {
    if (pattern == null) {
      return new LikePattern(null);
    }
    StringBuilder sb = new StringBuilder(pattern.length() + 8);
    sb.append("\\A");
    boolean hasEscape = escape != '\0';
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (hasEscape && c == escape) {
        if (i + 1 < pattern.length()) {
          appendLiteral(sb, pattern.charAt(++i));
        } else {
          // Trailing escape: treat as a literal escape character.
          appendLiteral(sb, c);
        }
      } else if (c == '%') {
        sb.append(".*");
      } else if (c == '_') {
        sb.append('.');
      } else {
        appendLiteral(sb, c);
      }
    }
    sb.append("\\z");
    return new LikePattern(Pattern.compile(sb.toString(), Pattern.DOTALL));
  }

  /**
   * Returns {@code true} if {@code value} matches this pattern. A {@code null} value never matches.
   */
  public boolean matches(String value) {
    if (value == null) {
      return false;
    }
    if (regex == null) {
      return true;
    }
    return regex.matcher(value).matches();
  }

  /** Returns {@code true} for the "match everything" pattern (compiled from {@code null}). */
  public boolean matchesAll() {
    return regex == null;
  }

  private static void appendLiteral(StringBuilder sb, char c) {
    switch (c) {
      case '\\':
      case '.':
      case '[':
      case ']':
      case '{':
      case '}':
      case '(':
      case ')':
      case '*':
      case '+':
      case '?':
      case '^':
      case '$':
      case '|':
        sb.append('\\').append(c);
        break;
      default:
        sb.append(c);
    }
  }
}
