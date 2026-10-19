/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in.web;

import static com.streampulse.feed.support.Fixtures.user;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streampulse.feed.application.FollowService;
import com.streampulse.feed.domain.FanOutStrategy;
import com.streampulse.feed.domain.InvalidFeedRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FollowController.class)
class FollowControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FollowService service;

    @Test
    @DisplayName("POST follow reports whether it changed anything and the new follower count")
    void followReportsTheOutcome() throws Exception {
        when(service.follow(user("bob"), user("alice"))).thenReturn(new FollowService.Result(true, 7));

        mvc.perform(post("/v1/users/alice/follow").param("followerId", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.followerCount").value(7));
    }

    @Test
    @DisplayName("DELETE follow removes it")
    void unfollowReportsTheOutcome() throws Exception {
        when(service.unfollow(user("bob"), user("alice"))).thenReturn(new FollowService.Result(true, 6));

        mvc.perform(delete("/v1/users/alice/follow").param("followerId", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.followerCount").value(6));
    }

    @Test
    @DisplayName("following yourself is a 400 problem+json")
    void aUserCannotFollowThemselves() throws Exception {
        when(service.follow(user("alice"), user("alice")))
                .thenThrow(new InvalidFeedRequestException("a user cannot follow themselves"));

        mvc.perform(post("/v1/users/alice/follow").param("followerId", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("a user cannot follow themselves"));
    }

    @Test
    @DisplayName("an invalid path id or a missing followerId is a 400 problem+json")
    void rejectsInvalidInput() throws Exception {
        mvc.perform(post("/v1/users/a b/follow").param("followerId", "bob"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/v1/users/alice/follow"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/v1/users/alice/follow").param("followerId", "x:y")).andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET followers reports the count and the strategy it selects")
    void summaryReportsTheStrategy() throws Exception {
        when(service.summary(user("star")))
                .thenReturn(new FollowService.Summary(user("star"), 12_000, FanOutStrategy.ON_READ));

        mvc.perform(get("/v1/users/star/followers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("star"))
                .andExpect(jsonPath("$.followers").value(12_000))
                .andExpect(jsonPath("$.strategy").value("ON_READ"));
    }
}
