/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in.web;

import static com.streampulse.feed.support.Fixtures.title;
import static com.streampulse.feed.support.Fixtures.user;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streampulse.feed.application.WatchService;
import com.streampulse.feed.application.WatchUnavailableException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WatchController.class)
class WatchControllerTest {

    private static final UUID EVENT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private WatchService service;

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        return mvc.perform(
                post("/v1/watching").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("a valid report is accepted with 202 and the event id")
    void acceptsAWatchWith202AndTheEventId() throws Exception {
        when(service.watch(eq(user("alice")), eq(title("dune")), eq(Optional.empty())))
                .thenReturn(EVENT);

        send("{\"userId\":\"alice\",\"titleId\":\"dune\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(EVENT.toString()));
    }

    @Test
    @DisplayName("a client-supplied event id is passed on, so the client's retry is deduplicated")
    void passesAClientSuppliedEventId() throws Exception {
        when(service.watch(any(), any(), any())).thenReturn(EVENT);

        send("{\"userId\":\"alice\",\"titleId\":\"dune\",\"eventId\":\"" + EVENT + "\"}")
                .andExpect(status().isAccepted());

        verify(service).watch(user("alice"), title("dune"), Optional.of(EVENT));
    }

    @Test
    @DisplayName("an id outside the allowed alphabet is a 400 problem+json and nothing is recorded")
    void rejectsAnInvalidIdWith400() throws Exception {
        send("{\"userId\":\"al:ice\",\"titleId\":\"dune\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a missing field is a 400 problem+json")
    void rejectsAMissingField() throws Exception {
        send("{\"userId\":\"alice\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verify(service, never()).watch(any(), any(), any());
    }

    @Test
    @DisplayName("an event id that is not a UUID is a 400 problem+json")
    void rejectsAMalformedEventId() throws Exception {
        send("{\"userId\":\"alice\",\"titleId\":\"dune\",\"eventId\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("when the report cannot be recorded the answer is 503, never 202")
    void reportsA503WhenTheEventCannotBeRecorded() throws Exception {
        when(service.watch(any(), any(), any()))
                .thenThrow(new WatchUnavailableException("broker down", new RuntimeException()));

        send("{\"userId\":\"alice\",\"titleId\":\"dune\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
