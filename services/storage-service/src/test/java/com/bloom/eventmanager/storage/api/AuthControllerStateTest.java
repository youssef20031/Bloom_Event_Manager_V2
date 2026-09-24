package com.bloom.eventmanager.storage.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.OAuthStateStore;
import com.bloom.eventmanager.storage.sync.AutoSyncScheduler;
import com.bloom.eventmanager.storage.sync.DriveSyncService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// R4: the brief's slice has no @EnableConfigurationProperties(StorageProperties.class); AuthController
// needs StorageProperties, so the slice would not start without it. Matches SyncControllerTest.
@WebMvcTest(AuthController.class)
@EnableConfigurationProperties(StorageProperties.class)
class AuthControllerStateTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriveAuthManager auth;

    @MockitoBean
    private OAuthStateStore states;

    @MockitoBean
    private AutoSyncScheduler scheduler;

    @MockitoBean
    private DriveSyncService sync;

    @Test
    void exchangesTheCodeWhenTheStateIsValid() throws Exception {
        when(states.consume("good-state")).thenReturn(true);

        mockMvc.perform(get("/api/v1/storage/auth/callback").param("code", "abc").param("state", "good-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("auth=success")));

        verify(auth).authenticate("abc");
    }

    @Test
    void refusesToExchangeACodeWithNoState() throws Exception {
        mockMvc.perform(get("/api/v1/storage/auth/callback").param("code", "abc"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("auth=error")));

        verify(auth, never()).authenticate(anyString());
    }

    @Test
    void refusesToExchangeACodeWithAnUnknownState() throws Exception {
        when(states.consume("forged")).thenReturn(false);

        mockMvc.perform(get("/api/v1/storage/auth/callback").param("code", "abc").param("state", "forged"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("auth=error")));

        verify(auth, never()).authenticate(anyString());
    }

    @Test
    void doesNotStartTheSyncSchedulerOnARejectedCallback() throws Exception {
        when(states.consume("forged")).thenReturn(false);

        mockMvc.perform(get("/api/v1/storage/auth/callback").param("code", "abc").param("state", "forged"))
                .andExpect(status().isFound());

        verify(scheduler, never()).start(org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * A Mongo outage while redeeming the state lands the browser tab back in the app
     * with ?auth=error, the same place every other failed consent ends up, rather
     * than on a raw 500 ProblemDetail.
     */
    @Test
    void sendsTheBrowserBackWithAnErrorWhenTheStateCannotBeChecked() throws Exception {
        when(states.consume("good-state")).thenThrow(new DataAccessResourceFailureException("Timed out waiting for a server"));

        mockMvc.perform(get("/api/v1/storage/auth/callback").param("code", "abc").param("state", "good-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("auth=error")));

        verify(auth, never()).authenticate(anyString());
        verify(scheduler, never()).start(org.mockito.ArgumentMatchers.anyLong());
    }
}
