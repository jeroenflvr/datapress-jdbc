package org.datapress.jdbc.internal.meta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LikePatternTest {

  @Test
  void nullPatternMatchesEverything() {
    LikePattern p = LikePattern.compile(null);
    assertThat(p.matchesAll()).isTrue();
    assertThat(p.matches("anything")).isTrue();
    assertThat(p.matches("")).isTrue();
    assertThat(p.matches(null)).isFalse();
  }

  @Test
  void emptyPatternMatchesOnlyEmptyString() {
    LikePattern p = LikePattern.compile("");
    assertThat(p.matches("")).isTrue();
    assertThat(p.matches("x")).isFalse();
  }

  @Test
  void literalMatchIsExact() {
    LikePattern p = LikePattern.compile("people");
    assertThat(p.matches("people")).isTrue();
    assertThat(p.matches("People")).isFalse();
    assertThat(p.matches("people2")).isFalse();
  }

  @Test
  void percentMatchesAnyRunIncludingEmpty() {
    assertThat(LikePattern.compile("%").matches("")).isTrue();
    assertThat(LikePattern.compile("%").matches("abc")).isTrue();
    assertThat(LikePattern.compile("a%").matches("a")).isTrue();
    assertThat(LikePattern.compile("a%").matches("abc")).isTrue();
    assertThat(LikePattern.compile("a%").matches("b")).isFalse();
    assertThat(LikePattern.compile("%c").matches("abc")).isTrue();
    assertThat(LikePattern.compile("a%c").matches("abc")).isTrue();
    assertThat(LikePattern.compile("a%c").matches("ac")).isTrue();
    assertThat(LikePattern.compile("a%c").matches("ab")).isFalse();
  }

  @Test
  void underscoreMatchesExactlyOneCharacter() {
    LikePattern p = LikePattern.compile("a_c");
    assertThat(p.matches("abc")).isTrue();
    assertThat(p.matches("aXc")).isTrue();
    assertThat(p.matches("ac")).isFalse();
    assertThat(p.matches("abbc")).isFalse();
  }

  @Test
  void escapeMakesWildcardsLiteral() {
    LikePattern percent = LikePattern.compile("50\\%", '\\');
    assertThat(percent.matches("50%")).isTrue();
    assertThat(percent.matches("50off")).isFalse();

    LikePattern underscore = LikePattern.compile("a\\_b", '\\');
    assertThat(underscore.matches("a_b")).isTrue();
    assertThat(underscore.matches("aXb")).isFalse();

    LikePattern escapeItself = LikePattern.compile("a\\\\b", '\\');
    assertThat(escapeItself.matches("a\\b")).isTrue();
  }

  @Test
  void trailingEscapeIsLiteral() {
    LikePattern p = LikePattern.compile("ab\\", '\\');
    assertThat(p.matches("ab\\")).isTrue();
  }

  @Test
  void regexMetacharactersInNamesAreMatchedLiterally() {
    // A dot in the pattern is literal, not "any char".
    LikePattern dot = LikePattern.compile("a.b");
    assertThat(dot.matches("a.b")).isTrue();
    assertThat(dot.matches("aXb")).isFalse();

    // Underscore still means "any single char" and matches a literal dot.
    LikePattern underscore = LikePattern.compile("a_b");
    assertThat(underscore.matches("a.b")).isTrue();

    for (String name : new String[] {"a(b)", "a[b]", "a{b}", "a+b", "a*b", "a^b$", "a|b"}) {
      assertThat(LikePattern.compile(name).matches(name)).as(name).isTrue();
      assertThat(LikePattern.compile(name).matches("aXb")).as(name).isFalse();
    }
  }

  @Test
  void newlinesAreMatchedByWildcards() {
    assertThat(LikePattern.compile("%").matches("line1\nline2")).isTrue();
    assertThat(LikePattern.compile("a_b").matches("a\nb")).isTrue();
  }

  @Test
  void escapingCanBeDisabled() {
    LikePattern p = LikePattern.compile("a\\%", '\0');
    // With escaping disabled, backslash is literal and % is a wildcard.
    assertThat(p.matches("a\\anything")).isTrue();
    assertThat(p.matches("a\\")).isTrue();
  }
}
