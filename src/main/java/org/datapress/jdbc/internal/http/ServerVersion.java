package org.datapress.jdbc.internal.http;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parsed {@code GET /version} response. Optional fields are {@code null} when the server omits them
 * (see docs/CONTRACT.md).
 */
public final class ServerVersion {

  private final String name;
  private final String version;
  private final String backend;
  private final String profile;
  private final String gitSha; // nullable
  private final String buildTime; // nullable
  private final String target; // nullable

  private ServerVersion(
      String name,
      String version,
      String backend,
      String profile,
      String gitSha,
      String buildTime,
      String target) {
    this.name = name;
    this.version = version;
    this.backend = backend;
    this.profile = profile;
    this.gitSha = gitSha;
    this.buildTime = buildTime;
    this.target = target;
  }

  /** Parses a {@code /version} JSON body defensively; missing fields become empty/null. */
  public static ServerVersion fromJson(JsonNode node) {
    return new ServerVersion(
        text(node, "name", ""),
        text(node, "version", "unknown"),
        text(node, "backend", "unknown"),
        text(node, "profile", ""),
        text(node, "git_sha", null),
        text(node, "build_time", null),
        text(node, "target", null));
  }

  private static String text(JsonNode node, String field, String fallback) {
    JsonNode child = node == null ? null : node.get(field);
    return child != null && !child.isNull() ? child.asText() : fallback;
  }

  public String name() {
    return name;
  }

  public String version() {
    return version;
  }

  /** Backend engine: {@code DuckDB}, {@code DataFusion}, or {@code unknown}. */
  public String backend() {
    return backend;
  }

  public String profile() {
    return profile;
  }

  public String gitSha() {
    return gitSha;
  }

  public String buildTime() {
    return buildTime;
  }

  public String target() {
    return target;
  }

  @Override
  public String toString() {
    return "ServerVersion{name=" + name + ", version=" + version + ", backend=" + backend + '}';
  }
}
