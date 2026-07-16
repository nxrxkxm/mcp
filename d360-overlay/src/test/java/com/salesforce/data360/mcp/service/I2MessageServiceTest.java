/*
 * Copyright (c) 2026, Salesforce, Inc.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.salesforce.data360.mcp.service;

import com.salesforce.data360.mcp.model.common.ApiException;
import com.salesforce.data360.mcp.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link I2MessageService}. External i2message endpoints are mocked with
 * {@link MockRestServiceServer}; HAR fixtures are generated per test with clearly fake
 * sentinel tokens (never real credentials).
 */
class I2MessageServiceTest {

    private static final String FAKE_FUEL2TOKEN = "FAKE-FUEL2-TOKEN-SENTINEL-000";
    private static final String FAKE_MID = "526000000";
    private static final String FAKE_I2_ACCESS_TOKEN = "FAKE-I2-ACCESS-TOKEN-SENTINEL-000";

    private static final String API_BASE = I2MessageService.DEFAULT_API_BASE_URL;
    private static final String RECEIVE_BASE = I2MessageService.DEFAULT_RECEIVE_BASE_URL;

    @TempDir
    Path tempDir;

    private MockRestServiceServer server;
    private I2MessageService service;
    private Path harFile;

    @BeforeEach
    void setUp() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        harFile = tempDir.resolve("mc-session.har");
        Files.writeString(harFile, defaultHar(), StandardCharsets.UTF_8);
        service = new I2MessageService(builder.build(), Map.of(
            I2MessageService.ENV_HAR_PATH, harFile.toString()
        ));
    }

    // ── HAR extraction ───────────────────────────────────────────────────────

    @Test
    void extractsFuel2TokenFromLastTokensEntry() {
        I2MessageService.HarAuth auth = service.extractHarAuth(harFile.toString());
        assertThat(auth.fuel2token()).isEqualTo(FAKE_FUEL2TOKEN);
    }

    @Test
    void extractsOrganizationIdFromTokenContextEntry() {
        I2MessageService.HarAuth auth = service.extractHarAuth(harFile.toString());
        assertThat(auth.mid()).isEqualTo(FAKE_MID);
    }

    @Test
    void decodesBase64EncodedResponseContent() throws IOException {
        String tokensJson = JsonUtil.toJson(Map.of("fuel2token", FAKE_FUEL2TOKEN));
        String encoded = Base64.getEncoder().encodeToString(tokensJson.getBytes(StandardCharsets.UTF_8));
        Path har = tempDir.resolve("base64.har");
        Files.writeString(har, harDocument(List.of(
            entry("https://mc.example.com/v2/tokens", 200, encoded, "base64"),
            entry("https://mc.example.com/fuelapi/platform/v1/tokenContext", 200,
                JsonUtil.toJson(Map.of("organization", Map.of("id", FAKE_MID))), null)
        )), StandardCharsets.UTF_8);

        I2MessageService.HarAuth auth = service.extractHarAuth(har.toString());
        assertThat(auth.fuel2token()).isEqualTo(FAKE_FUEL2TOKEN);
    }

    @Test
    void fallsBackToAccessTokenWhenNoFuel2TokenField() throws IOException {
        Path har = tempDir.resolve("accessTokenOnly.har");
        Files.writeString(har, harDocument(List.of(
            entry("https://mc.example.com/v2/tokens", 200,
                JsonUtil.toJson(Map.of("accessToken", FAKE_FUEL2TOKEN)), null),
            entry("https://mc.example.com/fuelapi/platform/v1/tokenContext", 200,
                JsonUtil.toJson(Map.of("organization", Map.of("id", FAKE_MID))), null)
        )), StandardCharsets.UTF_8);

        I2MessageService.HarAuth auth = service.extractHarAuth(har.toString());
        assertThat(auth.fuel2token()).isEqualTo(FAKE_FUEL2TOKEN);
    }

    @Test
    void missingTokensEntryProducesClearErrorWithoutSensitiveData() throws IOException {
        Path har = tempDir.resolve("noTokens.har");
        Files.writeString(har, harDocument(List.of(
            entry("https://mc.example.com/fuelapi/platform/v1/tokenContext", 200,
                JsonUtil.toJson(Map.of("organization", Map.of("id", FAKE_MID))), null)
        )), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.extractHarAuth(har.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No usable fuel2token found in the configured HAR");
    }

    @Test
    void missingOrganizationIdProducesClearError() throws IOException {
        Path har = tempDir.resolve("noMid.har");
        Files.writeString(har, harDocument(List.of(
            entry("https://mc.example.com/v2/tokens", 200,
                JsonUtil.toJson(Map.of("fuel2token", FAKE_FUEL2TOKEN)), null)
        )), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.extractHarAuth(har.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No organization.id found in tokenContext HAR response");
    }

    @Test
    void missingHarFileReportsPathOnly() {
        String bogus = tempDir.resolve("does-not-exist.har").toString();
        assertThatThrownBy(() -> service.extractHarAuth(bogus))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HAR file not found")
            .hasMessageContaining(bogus);
    }

    // ── Login ────────────────────────────────────────────────────────────────

    @Test
    void loginSendsFormEncodedCredentialsFromHar() {
        server.expect(requestTo(API_BASE + "/v1/accounts/login"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().formData(formData()))
            .andRespond(withSuccess(
                JsonUtil.toJson(Map.of("accessToken", FAKE_I2_ACCESS_TOKEN)),
                MediaType.APPLICATION_JSON));

        String token = service.login(service.extractHarAuth(harFile.toString()));
        assertThat(token).isEqualTo(FAKE_I2_ACCESS_TOKEN);
        server.verify();
    }

    @Test
    void loginHandlesNestedDataAccessToken() {
        server.expect(requestTo(API_BASE + "/v1/accounts/login"))
            .andRespond(withSuccess(
                JsonUtil.toJson(Map.of("data", Map.of("accessToken", FAKE_I2_ACCESS_TOKEN))),
                MediaType.APPLICATION_JSON));

        String token = service.login(service.extractHarAuth(harFile.toString()));
        assertThat(token).isEqualTo(FAKE_I2_ACCESS_TOKEN);
        server.verify();
    }

    @Test
    void loginWithoutAccessTokenFails() {
        server.expect(requestTo(API_BASE + "/v1/accounts/login"))
            .andRespond(withSuccess(JsonUtil.toJson(Map.of("ok", true)), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.login(service.extractHarAuth(harFile.toString())))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("i2message login succeeded without an accessToken");
        server.verify();
    }

    // ── Template registration ────────────────────────────────────────────────

    @Test
    void registrationSendsBearerTokenAndReturnsTemplateIdFromLocation() {
        expectLogin();
        server.expect(requestTo(API_BASE + "/v1/template"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + FAKE_I2_ACCESS_TOKEN))
            .andExpect(jsonPath("$.activityBasic.messageGroupId").value("journey-id-1"))
            .andExpect(jsonPath("$.activityBasic.messageGroupVersion").value(1))
            .andExpect(jsonPath("$.template.messageType").value("FRIENDTALK_TEXT"))
            .andExpect(jsonPath("$.template.kakaoProfile").value(I2MessageService.DEFAULT_KAKAO_PROFILE_ID))
            .andExpect(jsonPath("$.template.content").value("(광고) 신제품 안내"))
            .andRespond(withStatus(HttpStatus.CREATED)
                .headers(locationHeader(API_BASE + "/v1/template/98765")));

        Map<String, Object> result = register();

        assertThat(result.get("templateId")).isEqualTo("98765");
        assertThat(result.get("messageType")).isEqualTo("FRIENDTALK_TEXT");
        assertThat(result.get("registrationStatus")).isEqualTo(201);
        assertThat(result.get("authenticationSource")).isEqualTo("HAR");
        assertThat(result.get("eventExpression")).isEqualTo("{{Event.DEAudience-key-1.Phone}}");

        @SuppressWarnings("unchecked")
        Map<String, Object> activity = (Map<String, Object>) result.get("activityConfiguration");
        assertThat(activity.get("type")).isEqualTo("REST");
        String activityJson = JsonUtil.toJson(activity);
        assertThat(activityJson)
            .contains(RECEIVE_BASE + "/v1/journey/execute-message")
            .contains(RECEIVE_BASE + "/v1/journey/publish-message")
            .contains(RECEIVE_BASE + "/v1/journey/validate-message")
            .contains("\"templateId\":\"98765\"")
            .contains(I2MessageService.APPLICATION_EXTENSION_KEY)
            .contains("{{Event.DEAudience-key-1.Phone}}");
        server.verify();
    }

    @Test
    void registrationWithEmptyLocationHeaderFails() {
        expectLogin();
        server.expect(requestTo(API_BASE + "/v1/template"))
            .andRespond(withStatus(HttpStatus.CREATED));

        assertThatThrownBy(this::register)
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("no templateId in the Location header");
        server.verify();
    }

    // ── Validate ─────────────────────────────────────────────────────────────

    @Test
    void validateSucceedsOnHttp200WithEmptyBody() {
        expectLogin();
        server.expect(requestTo(RECEIVE_BASE + "/v1/journey/validate-message"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + FAKE_I2_ACCESS_TOKEN))
            .andExpect(jsonPath("$.templateId").value("98765"))
            .andExpect(jsonPath("$.interactionVersion").value("2"))
            .andExpect(jsonPath("$.activityObjectID").value("activity-final-1"))
            .andExpect(jsonPath("$.interactionKey").value("9256ecd7-e7e1-4b49-9a10-7dc618347a52"))
            .andExpect(jsonPath("$.originalDefinitionId").value("journey-id-1"))
            .andRespond(withStatus(HttpStatus.OK));

        Map<String, Object> result = service.validateJourneyActivity(
            "98765", "journey-id-1", "9256ecd7-e7e1-4b49-9a10-7dc618347a52",
            2, "activity-final-1", harFile.toString());

        assertThat(result)
            .containsEntry("valid", true)
            .containsEntry("statusCode", 200)
            .containsEntry("templateId", "98765")
            .containsEntry("journeyId", "journey-id-1")
            .containsEntry("activityObjectId", "activity-final-1");
        server.verify();
    }

    @Test
    void validateRejectsJourneyKeyOver36CharsBeforeAnyApiCall() {
        String longKey = "GLOW_SunCare_Retention_this_key_is_way_too_long";

        assertThatThrownBy(() -> service.validateJourneyActivity(
                "98765", "journey-id-1", longKey, 1, "activity-1", harFile.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("36");

        server.verify(); // no expectations registered — proves no HTTP call was made
    }

    // ── Security ─────────────────────────────────────────────────────────────

    @Test
    void tokensNeverAppearInResultsOrErrors() {
        expectLogin();
        server.expect(requestTo(API_BASE + "/v1/template"))
            .andRespond(withStatus(HttpStatus.CREATED)
                .headers(locationHeader("55555")));

        String resultJson = JsonUtil.toJson(register());
        assertThat(resultJson)
            .doesNotContain(FAKE_FUEL2TOKEN)
            .doesNotContain(FAKE_I2_ACCESS_TOKEN);

        // HarAuth.toString() must be masked
        assertThat(service.extractHarAuth(harFile.toString()).toString())
            .doesNotContain(FAKE_FUEL2TOKEN)
            .contains("****");

        // Exception paths must not leak tokens either
        try {
            I2MessageService.templateIdFromLocation("");
        } catch (ApiException e) {
            assertThat(e.getMessage())
                .doesNotContain(FAKE_FUEL2TOKEN)
                .doesNotContain(FAKE_I2_ACCESS_TOKEN);
        }
        server.verify();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> register() {
        return service.registerFriendtalkTemplate(
            "journey-id-1",
            "9256ecd7-e7e1-4b49-9a10-7dc618347a52",
            "GLOW_SunCare_Retention",
            1,
            "i2message-stg",
            "GLOW_SunCare_Retention FriendTalk",
            I2MessageService.DEFAULT_KAKAO_PROFILE_ID,
            "DEAudience-key-1",
            "Phone",
            "(광고) 신제품 안내",
            true,
            true,
            harFile.toString());
    }

    private void expectLogin() {
        server.expect(requestTo(API_BASE + "/v1/accounts/login"))
            .andRespond(withSuccess(
                JsonUtil.toJson(Map.of("accessToken", FAKE_I2_ACCESS_TOKEN)),
                MediaType.APPLICATION_JSON));
    }

    private static org.springframework.util.MultiValueMap<String, String> formData() {
        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.add("fuelapiRestHost", I2MessageService.DEFAULT_FUELAPI_REST_HOST);
        form.add("fuel2token", FAKE_FUEL2TOKEN);
        form.add("mid", FAKE_MID);
        return form;
    }

    private static HttpHeaders locationHeader(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, value);
        return headers;
    }

    private String defaultHar() {
        return harDocument(List.of(
            // an older, superseded tokens entry — extraction must pick the LAST one
            entry("https://mc.example.com/v2/tokens", 200,
                JsonUtil.toJson(Map.of("fuel2token", "FAKE-STALE-TOKEN")), null),
            entry("https://mc.example.com/unrelated/endpoint", 200,
                JsonUtil.toJson(Map.of("ignored", true)), null),
            entry("https://mc.example.com/v2/tokens", 200,
                JsonUtil.toJson(Map.of("fuel2token", FAKE_FUEL2TOKEN)), null),
            entry("https://mc.example.com/fuelapi/platform/v1/tokenContext", 200,
                JsonUtil.toJson(Map.of("organization", Map.of("id", FAKE_MID))), null)
        ));
    }

    private static String harDocument(List<Map<String, Object>> entries) {
        return JsonUtil.toJson(Map.of("log", Map.of("entries", entries)));
    }

    private static Map<String, Object> entry(String url, int status, String contentText, String encoding) {
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("text", contentText);
        if (encoding != null) content.put("encoding", encoding);
        return Map.of(
            "request", Map.of("method", "GET", "url", url),
            "response", Map.of("status", status, "content", content)
        );
    }
}
