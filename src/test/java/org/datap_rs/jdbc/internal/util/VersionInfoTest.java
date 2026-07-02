package org.datap_rs.jdbc.internal.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionInfoTest {

  @Test
  void driverVersionIsPopulatedFromBuild() {
    assertThat(VersionInfo.driverVersion()).isNotBlank().isNotEqualTo("unknown");
  }

  @Test
  void driverMajorAndMinorParse() {
    assertThat(VersionInfo.driverMajorVersion()).isGreaterThanOrEqualTo(0);
    assertThat(VersionInfo.driverMinorVersion()).isGreaterThanOrEqualTo(0);
  }
}
