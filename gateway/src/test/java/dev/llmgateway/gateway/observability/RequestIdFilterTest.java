package dev.llmgateway.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesIdWhenClientSendsNone() throws Exception {
        MockHttpServletResponse response = run(null, new AtomicReference<>());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[0-9a-f-]{36}");
    }

    @Test
    void reusesSafeIdFromClient() throws Exception {
        MockHttpServletResponse response = run("client-abc_123.4", new AtomicReference<>());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("client-abc_123.4");
    }

    @Test
    void replacesUnsafeIdThatCouldForgeLogLines() {
        assertThat(RequestIdFilter.resolveId("abc\nFAKE LOG LINE")).doesNotContain("FAKE");
        assertThat(RequestIdFilter.resolveId("x".repeat(65))).hasSize(36);
        assertThat(RequestIdFilter.resolveId("")).hasSize(36);
    }

    @Test
    void idIsInMdcDuringRequestAndClearedAfter() throws Exception {
        AtomicReference<String> seenInside = new AtomicReference<>();

        run("abc", seenInside);

        assertThat(seenInside.get()).isEqualTo("abc");
        assertThat(MDC.get(LogFields.REQUEST_ID)).isNull();
    }

    private MockHttpServletResponse run(String incomingId, AtomicReference<String> mdcInside) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        if (incomingId != null) {
            request.addHeader(RequestIdFilter.HEADER, incomingId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                mdcInside.set(MDC.get(LogFields.REQUEST_ID));
            }
        };
        filter.doFilter(request, response, new MockFilterChain(servlet));
        return response;
    }
}
