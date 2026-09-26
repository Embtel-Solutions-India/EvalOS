package com.ie.evalos.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** A draft is two files of up to max-file-size each in one request (Unit 58). */
class UploadLimitsTest {

	@Test
	void oneRequestHoldsTwoFullSizeFiles() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));
		var props = yaml.getObject();

		assertThat(props.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("${UPLOAD_MAX_SIZE:15MB}");
		assertThat(props.getProperty("spring.servlet.multipart.max-request-size"))
				.isEqualTo("${UPLOAD_MAX_REQUEST_SIZE:32MB}");
	}
}
