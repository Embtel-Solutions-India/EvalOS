package com.ie.evalos.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void healthIsUpAndWrappedInTheEnvelope() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.status").value("UP"))
				.andExpect(jsonPath("$.data.service").value("evalos"))
				.andExpect(jsonPath("$.data.time").exists())
				.andExpect(jsonPath("$.error").doesNotExist());
	}
}
