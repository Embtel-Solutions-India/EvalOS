package com.ie.evalos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.ie.evalos.job.JobProperties;

/**
 * <p>{@code JobProperties} is registered explicitly rather than by
 * {@code @ConfigurationPropertiesScan}: it is the only bound-properties class in the codebase —
 * everything else reads a single value with {@code @Value} — and a scan would quietly adopt
 * every future one. Naming it here keeps the list of things bound from configuration readable.
 */
@SpringBootApplication
@EnableConfigurationProperties(JobProperties.class)
public class EvalOsApplication {

	public static void main(String[] args) {
		SpringApplication.run(EvalOsApplication.class, args);
	}
}
