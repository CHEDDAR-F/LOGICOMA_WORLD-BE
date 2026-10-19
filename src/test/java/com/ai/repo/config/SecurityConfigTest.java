package com.ai.repo.config;

import com.ai.repo.jwt.JwtAuthenticationFilter;
import com.ai.repo.jwt.JwtProvider;
import com.ai.repo.service.AgentService;
import com.ai.repo.entity.User;
import com.ai.repo.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@ContextConfiguration(classes = {
        SecurityConfigTest.ProbeController.class,
        SecurityConfig.class,
        JwtAuthenticationFilter.class
})
class SecurityConfigTest {
    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private JwtProvider jwtProvider;
    @MockBean
    private AgentService agentService;
    @MockBean
    private UserMapper userMapper;

    @Test
    void readinessIsAnonymousReadOnlyWithoutOpeningOtherActuatorOrBusinessRoutes() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
        mockMvc.perform(post("/actuator/health/readiness")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/memories/11")).andExpect(status().isUnauthorized());
    }

    @Test
    void existingJwtStopsAuthenticatingImmediatelyAfterAccountIsDisabled() throws Exception {
        when(jwtProvider.validateAccessToken("header.payload.signature")).thenReturn(1L);
        User user = new User();
        user.setStatus("ACTIVE");
        when(userMapper.selectById(1L)).thenReturn(user);

        mockMvc.perform(get("/security-probe").header("Authorization", "Bearer header.payload.signature"))
                .andExpect(status().isOk());

        user.setStatus("DISABLED");
        mockMvc.perform(get("/security-probe").header("Authorization", "Bearer header.payload.signature"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void unauthenticatedRequestDoesNotCreateSessionCookie() throws Exception {
        mockMvc.perform(get("/security-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void onlyTheTwoPublishedShareRoutesAreAnonymous() throws Exception {
        mockMvc.perform(get("/api/playground/shares/example"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/playground/shares/example/landing"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/playground/shares/example/private"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/playground/activities/23/share"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/admin/playground/shares/23/remove"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/actuator/health/readiness")
        String readiness() { return "UP"; }
        @GetMapping("/security-probe")
        String probe() {
            return "ok";
        }

        @GetMapping({"/api/playground/shares/example", "/api/playground/shares/example/landing"})
        String publicShare() {
            return "public";
        }
    }
}
