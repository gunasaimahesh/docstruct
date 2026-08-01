package com.docstruct.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.docstruct.config.RateLimitProperties;
import com.docstruct.config.UploadProperties;
import com.docstruct.controller.CollectionController;
import com.docstruct.exception.GlobalExceptionHandler;
import com.docstruct.service.CollectionService;
import com.docstruct.service.IngestionService;

/**
 * The HTTP contract of the limiter, exercised through real dispatch: the status,
 * the {@code Retry-After} header, the shared error body, and — the part that
 * actually saves money — that a throttled upload never reaches the handler.
 *
 * The exact refill arithmetic is {@link TokenBucketTest}'s job; this test only
 * cares that the number surfaced to the client is a plausible countdown.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    private static final int CAPACITY = 2;
    private static final Duration WINDOW = Duration.ofSeconds(60);
    /** Capacity 2 over 60 seconds means a token every 30 seconds. */
    private static final long REFILL_INTERVAL_SECONDS = 30L;

    private static final String CLIENT = "203.0.113.7";
    private static final String OTHER_CLIENT = "198.51.100.4";

    @Mock
    private CollectionService collectionService;
    @Mock
    private IngestionService ingestionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvcLimitedTo(new RateLimitProperties(true, CAPACITY, WINDOW, 100));
    }

    private MockMvc mockMvcLimitedTo(RateLimitProperties properties) {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(new RateLimiter(properties));
        return MockMvcBuilders
                .standaloneSetup(new CollectionController(collectionService, ingestionService))
                .addMappedInterceptors(new String[] {"/api/collections"}, interceptor)
                .setControllerAdvice(new GlobalExceptionHandler(new UploadProperties(10 * 1024 * 1024)))
                .build();
    }

    private static RequestPostProcessor from(String clientIp) {
        return request -> {
            request.setRemoteAddr(clientIp);
            return request;
        };
    }

    private static RequestBuilder upload(String clientIp) {
        return multipart("/api/collections")
                .file(new MockMultipartFile("file", "invoices.csv", "text/csv",
                        "Vendor\nAcme".getBytes(StandardCharsets.UTF_8)))
                .with(from(clientIp));
    }

    private void spendWholeBudget(String clientIp) throws Exception {
        for (int i = 0; i < CAPACITY; i++) {
            mockMvc.perform(upload(clientIp)).andExpect(status().isOk());
        }
    }

    @Test
    void uploadsWithinTheBudgetReachTheHandler() throws Exception {
        spendWholeBudget(CLIENT);

        verify(ingestionService, times(CAPACITY)).ingestNewCollection(any(), any());
    }

    @Test
    void exceedingTheBudgetReturns429WithARetryAfterHeader() throws Exception {
        spendWholeBudget(CLIENT);

        MvcResult result = mockMvc.perform(upload(CLIENT))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.error").value(containsString("try again")))
                .andReturn();

        String retryAfter = result.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(Long.parseLong(retryAfter))
                .as("a countdown the client can act on, never zero")
                .isBetween(1L, REFILL_INTERVAL_SECONDS);
    }

    @Test
    void aThrottledUploadNeverReachesExtraction() throws Exception {
        spendWholeBudget(CLIENT);

        mockMvc.perform(upload(CLIENT)).andExpect(status().isTooManyRequests());

        // The whole point: the rejected request cost no LLM call.
        verify(ingestionService, times(CAPACITY)).ingestNewCollection(any(), any());
    }

    @Test
    void readsAreNotChargedAgainstTheBudget() throws Exception {
        spendWholeBudget(CLIENT);

        // Same path, same client — only the POST spends an extraction.
        mockMvc.perform(get("/api/collections").with(from(CLIENT)))
                .andExpect(status().isOk());
    }

    @Test
    void oneClientCannotExhaustAnother() throws Exception {
        spendWholeBudget(CLIENT);

        mockMvc.perform(upload(OTHER_CLIENT)).andExpect(status().isOk());
        mockMvc.perform(upload(CLIENT)).andExpect(status().isTooManyRequests());
    }

    @Test
    void disablingTheLimiterLetsEverythingThrough() throws Exception {
        mockMvc = mockMvcLimitedTo(new RateLimitProperties(false, CAPACITY, WINDOW, 100));

        for (int i = 0; i < CAPACITY * 3; i++) {
            mockMvc.perform(upload(CLIENT)).andExpect(status().isOk());
        }
    }
}
