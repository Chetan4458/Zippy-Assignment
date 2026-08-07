package com.zippy.backend;

import com.zippy.backend.security.SecurityHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:zippyprodsecurity;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=prod-test-db-password",
    "zippy.automation-enabled=false",
    "zippy.security.api-key.value=prod-integration-api-key-32-chars-minimum",
    "zippy.security.webhooks.secrets.fastship=prod-fastship-secret-32-characters-minimum",
    "zippy.security.webhooks.secrets.quickexpress=prod-quickexpress-secret-32-characters",
    "zippy.security.webhooks.secrets.reliable=prod-reliable-secret-32-characters-minimum"
})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class ZippyProdSecurityTest {
  private static final String API_KEY = "prod-integration-api-key-32-chars-minimum";

  @Autowired
  private MockMvc mockMvc;

  @Test
  void keepsHealthPublicButDeniesDevelopmentAndSimulationMutations() throws Exception {
    mockMvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));

    mockMvc.perform(post("/api/dev/runtime-flags")
            .header(SecurityHeaders.API_KEY, API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.message").value("Access denied"));

    mockMvc.perform(post("/api/mock-carriers/ZPY-ORD-10001/advance")
            .header(SecurityHeaders.API_KEY, API_KEY))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/fastship/api/v1/shipments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isUnauthorized());
  }
}
