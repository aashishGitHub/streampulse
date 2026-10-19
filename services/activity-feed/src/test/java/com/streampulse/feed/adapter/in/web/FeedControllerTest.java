/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in.web;

import static com.streampulse.feed.support.Fixtures.NOW;
import static com.streampulse.feed.support.Fixtures.entry;
import static com.streampulse.feed.support.Fixtures.user;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streampulse.feed.application.FeedService;
import com.streampulse.feed.domain.InvalidCursorException;
import com.streampulse.feed.domain.RankedEntry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FeedController.class)
class FeedControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FeedService service;

    @Test
    @DisplayName("a page lists who is watching what, with the score and the cursor for the next page")
    void returnsAPage() throws Exception {
        var item = new RankedEntry(entry("alice", "dune", NOW), 1.5);
        when(service.read(user("reader"), Optional.empty(), 20))
                .thenReturn(new FeedService.Result(List.of(item), Optional.of("next-token")));

        mvc.perform(get("/v1/feed").param("userId", "reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].authorId").value("alice"))
                .andExpect(jsonPath("$.items[0].titleId").value("dune"))
                .andExpect(jsonPath("$.items[0].watchedAt").value("2026-10-13T12:00:00Z"))
                .andExpect(jsonPath("$.items[0].score").value(1.5))
                .andExpect(jsonPath("$.nextCursor").value("next-token"));
    }

    @Test
    @DisplayName("the last page has a null cursor")
    void lastPageHasNoCursor() throws Exception {
        when(service.read(any(), any(), anyInt())).thenReturn(new FeedService.Result(List.of(), Optional.empty()));

        mvc.perform(get("/v1/feed").param("userId", "reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("the cursor and limit are passed through")
    void passesTheCursorAndLimit() throws Exception {
        when(service.read(any(), any(), anyInt())).thenReturn(new FeedService.Result(List.of(), Optional.empty()));

        mvc.perform(get("/v1/feed")
                        .param("userId", "reader")
                        .param("cursor", "abc")
                        .param("limit", "5"))
                .andExpect(status().isOk());

        verify(service).read(user("reader"), Optional.of("abc"), 5);
    }

    @Test
    @DisplayName("a limit outside 1..100 is a 400 problem+json")
    void rejectsALimitOutsideTheBound() throws Exception {
        for (String limit : new String[] {"0", "-1", "101", "abc"}) {
            mvc.perform(get("/v1/feed").param("userId", "reader").param("limit", limit))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a missing or invalid userId is a 400 problem+json")
    void rejectsAnInvalidUserId() throws Exception {
        mvc.perform(get("/v1/feed")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/feed").param("userId", "a:b"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a cursor the service rejects is a 400 problem+json that does not echo it")
    void rejectsAnInvalidCursorWith400() throws Exception {
        when(service.read(any(), any(), anyInt()))
                .thenThrow(new InvalidCursorException("the cursor is not valid for this request"));

        mvc.perform(get("/v1/feed").param("userId", "reader").param("cursor", "TAMPERED"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("the cursor is not valid for this request"));
    }

    @Test
    @DisplayName("an oversized cursor is rejected before it reaches the service")
    void rejectsAnOversizedCursor() throws Exception {
        mvc.perform(get("/v1/feed").param("userId", "reader").param("cursor", "A".repeat(600)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a store being down is a 503 problem+json, not a stack trace")
    void reportsA503WhenAStoreIsDown() throws Exception {
        when(service.read(any(), any(), anyInt())).thenThrow(new QueryTimeoutException("postgres down"));

        mvc.perform(get("/v1/feed").param("userId", "reader"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
