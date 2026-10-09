package com.ie.evalos.security;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdminAllowlist} opens six areas to the Admin by prefix, so a new endpoint under one is reachable
 * by the Admin whatever the controller does. The only thing standing between it and the Admin is the
 * handler's own {@code @PreAuthorize}; this fails the build for a handler under those areas that has none,
 * on the method or on its class.
 */
class AdminAreasPreAuthorizeTest {

	@Test
	void everyHandlerUnderAnAdminAreaDeclaresItsOwnRoleGate() throws Exception {
		var scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

		List<String> ungated = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents("com.ie.evalos")) {
			Class<?> controller = Class.forName(definition.getBeanClassName());
			RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
			for (Method method : controller.getDeclaredMethods()) {
				RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
				if (mapping == null) {
					continue;
				}
				for (String path : fullPaths(base, mapping)) {
					boolean inArea = AdminAllowlist.ADMIN_AREAS.stream()
							.anyMatch(area -> path.equals(area) || path.startsWith(area + "/"));
					boolean gated = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class) != null
							|| AnnotatedElementUtils.findMergedAnnotation(controller, PreAuthorize.class) != null;
					if (inArea && !gated) {
						ungated.add(controller.getSimpleName() + "." + method.getName() + " " + path);
					}
				}
			}
		}

		assertThat(ungated)
				.as("handlers under an Admin-open area with no @PreAuthorize — add one, or move the route")
				.isEmpty();
	}

	private static List<String> fullPaths(RequestMapping base, RequestMapping method) {
		List<String> prefixes = base == null || base.value().length == 0 ? List.of("") : List.of(base.value());
		List<String> suffixes = method.value().length == 0 ? List.of("") : List.of(method.value());
		List<String> out = new ArrayList<>();
		for (String prefix : prefixes) {
			for (String suffix : suffixes) {
				out.add((prefix + suffix).replaceAll("/+$", ""));
			}
		}
		return out;
	}
}
