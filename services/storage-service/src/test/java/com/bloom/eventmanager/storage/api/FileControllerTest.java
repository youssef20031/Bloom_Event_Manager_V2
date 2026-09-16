package com.bloom.eventmanager.storage.api;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.conventions.CompanyFolder;
import com.bloom.eventmanager.storage.conventions.DriveFolderConventions;
import com.bloom.eventmanager.storage.conventions.NamedFolderUpload;
import com.bloom.eventmanager.storage.drive.DriveClient;
import com.bloom.eventmanager.storage.drive.UploadResult;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FileController.class)
@EnableConfigurationProperties(StorageProperties.class)
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriveClient drive;

    @MockitoBean
    private DriveFolderConventions conventions;

    @MockitoBean
    private DriveAuthManager auth;

    @Test
    void streamsFileContentAsOctetStream() throws Exception {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.download("f1")).thenReturn(new byte[] { 1, 2, 3 });

        mockMvc.perform(get("/api/v1/storage/files/f1/content"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(new byte[] { 1, 2, 3 }));
    }

    @Test
    void downloadWithoutDriveAuthIs401() throws Exception {
        when(auth.isAuthenticated()).thenReturn(false);

        mockMvc.perform(get("/api/v1/storage/files/f1/content")).andExpect(status().isUnauthorized());
    }

    @Test
    void uploadsBase64ContentIntoAFolder() throws Exception {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.upload("folder", "x.pdf", new byte[] { 9 }, "application/pdf")).thenReturn(new UploadResult("id", "link"));

        mockMvc.perform(post("/api/v1/storage/files").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\": \"folder\", \"fileName\": \"x.pdf\", \"mimeType\": \"application/pdf\", \"contentBase64\": \""
                                + Base64.getEncoder().encodeToString(new byte[] { 9 }) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("id"))
                .andExpect(jsonPath("$.webViewLink").value("link"));
    }

    @Test
    void listsCompaniesUnderTheConfiguredRoot() throws Exception {
        when(auth.isAuthenticated()).thenReturn(true);
        when(conventions.listCompanyFolders(any())).thenReturn(List.of(new CompanyFolder("f", "Acme", 2)));

        mockMvc.perform(get("/api/v1/storage/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Acme"))
                .andExpect(jsonPath("$[0].briefCount").value(2));
    }

    @Test
    void uploadsACompanyFileThroughTheConventions() throws Exception {
        when(auth.isAuthenticated()).thenReturn(true);
        when(conventions.uploadFileToNamedFolder(any(), eq("Acme"), eq("brief.pdf"), eq(new byte[] { 7 }), eq("application/pdf")))
                .thenReturn(new NamedFolderUpload("folder", "file", "link"));

        mockMvc.perform(post("/api/v1/storage/company-files").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"Acme\", \"fileName\": \"brief.pdf\", \"mimeType\": \"application/pdf\", \"contentBase64\": \""
                                + Base64.getEncoder().encodeToString(new byte[] { 7 }) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").value("folder"))
                .andExpect(jsonPath("$.fileId").value("file"));
    }

    @Test
    void rejectsACompanyFileWithoutAName() throws Exception {
        when(auth.isAuthenticated()).thenReturn(true);

        mockMvc.perform(post("/api/v1/storage/company-files").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"\", \"fileName\": \"b.pdf\", \"mimeType\": \"application/pdf\", \"contentBase64\": \"AA==\"}"))
                .andExpect(status().isBadRequest());
    }
}
