package com.ie.evalos.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSafeTest {

	@Test
	void anAddressKeepsItsFirstLetterAndDomainOnly() {
		assertThat(LogSafe.email("ana.perez@example.com")).isEqualTo("a***@example.com");
		assertThat(LogSafe.email("@example.com")).isEqualTo("***");
		assertThat(LogSafe.email("not-an-address")).isEqualTo("***");
		assertThat(LogSafe.email(null)).isEqualTo("null");
	}
}
