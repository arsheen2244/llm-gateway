package dev.llmgateway.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.llmgateway.gateway.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyAuthFilterTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
            new ClientRegistry(new AuthProperties(List.of(
                    new AuthProperties.Client("team-a", TestKeys.sha256("good-key"), 5, 500)))),
            json);

    @Test
    void validKeyPassesAndAttachesIdentity() throws Exception {
        MockHttpServletRequest request = request("/v1/chat/completions", "Bearer good-key");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull(); // the chain continued
        assertThat(request.getAttribute(ClientIdentity.REQUEST_ATTRIBUTE))
                .isEqualTo(new ClientIdentity("team-a", 5, 500));
    }

    @Test
    void missingKeyIs401MissingApiKey() throws Exception {
        assertRejected(request("/v1/chat/completions", null), "missing_api_key");
    }

    @Test
    void nonBearerHeaderIs401MissingApiKey() throws Exception {
        assertRejected(request("/v1/chat/completions", "Basic Zm9vOmJhcg=="), "missing_api_key");
    }

    @Test
    void unknownKeyIs401InvalidApiKey() throws Exception {
        assertRejected(request("/v1/chat/completions", "Bearer wrong-key"), "invalid_api_key");
    }

    @Test
    void pathsOutsideV1AreNotChecked() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/error", null), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private void assertRejected(MockHttpServletRequest request, String expectedCode) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull(); // the chain did not continue
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.at("/error/code").asText()).isEqualTo(expectedCode);
    }

    private static MockHttpServletRequest request(String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}
