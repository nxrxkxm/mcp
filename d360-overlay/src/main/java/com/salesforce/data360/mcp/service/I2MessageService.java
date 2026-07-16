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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * i2message (Kakao FriendTalk) integration for Marketing Cloud Journey REST activities.
 *
 * <p>Authentication is fully automatic: Marketing Cloud credentials (fuel2token and MID)
 * are extracted from a locally exported HAR capture of an authenticated Marketing Cloud
 * UI session, exchanged for an i2message access token via the i2message login endpoint,
 * and used in memory only. Tokens are never logged, never persisted, and never included
 * in tool results or exception messages.
 *
 * <p>Configuration (environment variables, all optional):
 * <ul>
 *   <li>{@code I2MESSAGE_HAR_PATH} — HAR file path (default: the workstation export path)</li>
 *   <li>{@code I2MESSAGE_FUELAPI_REST_HOST} — Marketing Cloud REST host sent to the
 *       i2message login endpoint</li>
 *   <li>{@code I2MESSAGE_API_BASE_URL} — i2message API base (login/template)</li>
 *   <li>{@code I2MESSAGE_RECEIVE_BASE_URL} — i2message receive base (execute/publish/validate)</li>
 * </ul>
 */
@Service
public class I2MessageService {

    // ── Defaults (current BU) ────────────────────────────────────────────────
    public static final String DEFAULT_HAR_PATH =
        "C:\\Users\\디센트릭\\Desktop\\전체_mc.s12.exacttarget.com.har.com";
    public static final String DEFAULT_FUELAPI_REST_HOST =
        "https://mc792x8fthwpxhp4f3pcghck3ls4.rest.marketingcloudapis.com";
    public static final String DEFAULT_API_BASE_URL = "https://stg.mc.api.i2message.io";
    public static final String DEFAULT_RECEIVE_BASE_URL = "https://stg.mc.receive.i2message.io";

    public static final String DEFAULT_KAKAO_PROFILE_ID = "a5091fc6-8615-404c-9f52-f9558beecf69";
    public static final String DEFAULT_ACTIVITY_NAME = "i2message-stg";
    public static final String MESSAGE_TYPE_FRIENDTALK_TEXT = "FRIENDTALK_TEXT";
    public static final String APPLICATION_EXTENSION_KEY = "5a170bea-dc51-42f2-9c49-338070b7282d";
    public static final String APPLICATION_EXTENSION_ID = "df4cdf8c-9199-4aca-bb31-1f184f6a04c4";

    static final String ENV_HAR_PATH = "I2MESSAGE_HAR_PATH";
    static final String ENV_FUELAPI_REST_HOST = "I2MESSAGE_FUELAPI_REST_HOST";
    static final String ENV_API_BASE_URL = "I2MESSAGE_API_BASE_URL";
    static final String ENV_RECEIVE_BASE_URL = "I2MESSAGE_RECEIVE_BASE_URL";

    static final int MAX_JOURNEY_KEY_LENGTH = 36;

    private static final String LOGIN_PATH = "/v1/accounts/login";
    private static final String TEMPLATE_PATH = "/v1/template";
    private static final String EXECUTE_PATH = "/v1/journey/execute-message";
    private static final String PUBLISH_PATH = "/v1/journey/publish-message";
    private static final String VALIDATE_PATH = "/v1/journey/validate-message";

    private final RestClient restClient;
    private final Map<String, String> env;

    public I2MessageService() {
        this(RestClient.builder().build(), System.getenv());
    }

    /** Test constructor: inject a (mock-bound) RestClient and a fake environment. */
    I2MessageService(RestClient restClient, Map<String, String> env) {
        this.restClient = restClient;
        this.env = env;
    }

    // ── HAR credential extraction ────────────────────────────────────────────

    /**
     * Marketing Cloud credentials extracted from the HAR. Never expose the token:
     * {@link #toString()} is masked so accidental logging cannot leak it.
     */
    static final class HarAuth {
        private final String fuel2token;
        private final String mid;

        HarAuth(String fuel2token, String mid) {
            this.fuel2token = fuel2token;
            this.mid = mid;
        }

        String fuel2token() { return fuel2token; }
        String mid() { return mid; }

        @Override
        public String toString() {
            return "HarAuth[fuel2token=****, mid=" + mid + "]";
        }
    }

    /** Resolves the HAR path from the explicit argument, environment, or built-in default. */
    String resolveHarPath(String explicitHarPath) {
        if (!isBlank(explicitHarPath)) return explicitHarPath.trim();
        String fromEnv = env.get(ENV_HAR_PATH);
        if (!isBlank(fromEnv)) return fromEnv.trim();
        return DEFAULT_HAR_PATH;
    }

    String resolveFuelapiRestHost() {
        String fromEnv = env.get(ENV_FUELAPI_REST_HOST);
        return isBlank(fromEnv) ? DEFAULT_FUELAPI_REST_HOST : fromEnv.trim();
    }

    String apiBaseUrl() {
        String fromEnv = env.get(ENV_API_BASE_URL);
        return isBlank(fromEnv) ? DEFAULT_API_BASE_URL : trimTrailingSlash(fromEnv.trim());
    }

    String receiveBaseUrl() {
        String fromEnv = env.get(ENV_RECEIVE_BASE_URL);
        return isBlank(fromEnv) ? DEFAULT_RECEIVE_BASE_URL : trimTrailingSlash(fromEnv.trim());
    }

    /**
     * Parses the HAR export and extracts the newest usable fuel2token and MID.
     *
     * <p>fuel2token: last successful entry whose request URL matches {@code /tokens};
     * the response JSON's {@code fuel2token}, falling back to {@code accessToken}.
     * MID: last successful entry whose request URL contains
     * {@code /fuelapi/platform/v1/tokenContext}; the response JSON's {@code organization.id}.
     */
    HarAuth extractHarAuth(String harPath) {
        Path path = Path.of(harPath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("HAR file not found: " + harPath
                + " (set " + ENV_HAR_PATH + " or pass harPath)");
        }
        Map<?, ?> har;
        try {
            har = JsonUtil.fromJson(Files.readString(path, StandardCharsets.UTF_8), Map.class);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Could not parse the configured HAR file as JSON");
        }

        List<Map<?, ?>> entries = harEntries(har);

        String fuel2token = null;
        for (Map<?, ?> entry : entries) {
            if (!requestUrlMatches(entry, "/tokens")) continue;
            Map<?, ?> body = successfulJsonResponseBody(entry);
            if (body == null) continue;
            String candidate = stringValue(body.get("fuel2token"));
            if (isBlank(candidate)) candidate = stringValue(body.get("accessToken"));
            if (!isBlank(candidate)) fuel2token = candidate;   // keep the LAST usable one
        }
        if (isBlank(fuel2token)) {
            throw new IllegalArgumentException("No usable fuel2token found in the configured HAR");
        }

        String mid = null;
        for (Map<?, ?> entry : entries) {
            if (!requestUrlMatches(entry, "/fuelapi/platform/v1/tokenContext")) continue;
            Map<?, ?> body = successfulJsonResponseBody(entry);
            if (body == null) continue;
            Object organization = body.get("organization");
            if (organization instanceof Map<?, ?> orgMap) {
                String candidate = stringValue(orgMap.get("id"));
                if (!isBlank(candidate)) mid = candidate;      // keep the LAST usable one
            }
        }
        if (isBlank(mid)) {
            throw new IllegalArgumentException("No organization.id found in tokenContext HAR response");
        }

        return new HarAuth(fuel2token, mid);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> harEntries(Map<?, ?> har) {
        Object log = har.get("log");
        if (log instanceof Map<?, ?> logMap) {
            Object entries = logMap.get("entries");
            if (entries instanceof List<?> list) {
                List<Map<?, ?>> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) result.add(m);
                }
                return result;
            }
        }
        throw new IllegalArgumentException("Configured HAR file has no log.entries array");
    }

    private static boolean requestUrlMatches(Map<?, ?> entry, String needle) {
        Object request = entry.get("request");
        if (!(request instanceof Map<?, ?> requestMap)) return false;
        String url = stringValue(requestMap.get("url"));
        return url != null && url.contains(needle);
    }

    /** Returns the parsed JSON body of a 2xx response, decoding base64 content if flagged. Null if unusable. */
    private static Map<?, ?> successfulJsonResponseBody(Map<?, ?> entry) {
        Object response = entry.get("response");
        if (!(response instanceof Map<?, ?> responseMap)) return null;
        int status = intValue(responseMap.get("status"));
        if (status < 200 || status >= 300) return null;
        Object content = responseMap.get("content");
        if (!(content instanceof Map<?, ?> contentMap)) return null;
        String text = stringValue(contentMap.get("text"));
        if (isBlank(text)) return null;
        if ("base64".equalsIgnoreCase(stringValue(contentMap.get("encoding")))) {
            try {
                text = new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        try {
            return JsonUtil.fromJson(text, Map.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── i2message login ──────────────────────────────────────────────────────

    /**
     * Exchanges the HAR-derived Marketing Cloud credentials for an i2message access token.
     * The token lives in memory only.
     */
    String login(HarAuth auth) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("fuelapiRestHost", resolveFuelapiRestHost());
        form.add("fuel2token", auth.fuel2token());
        form.add("mid", auth.mid());

        Map<?, ?> body;
        try {
            body = restClient.post()
                .uri(apiBaseUrl() + LOGIN_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        } catch (RestClientResponseException e) {
            // Never include the response body here: login errors may echo submitted form fields.
            throw new ApiException(e.getStatusCode().value(),
                "i2message login failed with HTTP " + e.getStatusCode().value(), LOGIN_PATH);
        }

        String accessToken = body == null ? null : stringValue(body.get("accessToken"));
        if (isBlank(accessToken) && body != null && body.get("data") instanceof Map<?, ?> data) {
            accessToken = stringValue(data.get("accessToken"));
        }
        if (isBlank(accessToken)) {
            throw new ApiException(502, "i2message login succeeded without an accessToken", LOGIN_PATH);
        }
        return accessToken;
    }

    // ── FriendTalk template registration ─────────────────────────────────────

    /**
     * Registers a Kakao FriendTalk template for the given Journey and returns the
     * new templateId plus a ready-to-insert Journey REST activity configuration.
     */
    public Map<String, Object> registerFriendtalkTemplate(
        String journeyId,
        String journeyKey,
        String journeyName,
        int journeyVersion,
        String activityName,
        String messageName,
        String kakaoProfileId,
        String eventDefinitionKey,
        String recipientNumberField,
        String content,
        boolean advertEnabled,
        boolean redeliveryEnabled,
        String harPath
    ) {
        requireJourneyKeyLength(journeyKey);

        HarAuth auth = extractHarAuth(resolveHarPath(harPath));
        String accessToken = login(auth);

        Map<String, Object> registrationBody = buildRegistrationBody(
            journeyId, journeyName, journeyVersion, activityName, messageName,
            kakaoProfileId, recipientNumberField, content, advertEnabled, redeliveryEnabled);

        ResponseEntity<Void> response;
        try {
            response = restClient.post()
                .uri(apiBaseUrl() + TEMPLATE_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                .body(registrationBody)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new ApiException(e.getStatusCode().value(),
                "i2message template registration failed with HTTP " + e.getStatusCode().value()
                    + truncatedBody(e), TEMPLATE_PATH);
        }

        HttpStatusCode status = response.getStatusCode();
        String templateId = templateIdFromLocation(response.getHeaders().getFirst(HttpHeaders.LOCATION));

        String phoneExpression = eventExpression(eventDefinitionKey, recipientNumberField);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateId", templateId);
        result.put("messageType", MESSAGE_TYPE_FRIENDTALK_TEXT);
        result.put("registrationStatus", status.value());
        result.put("activityConfiguration", buildRestActivity(
            journeyId, activityName, templateId, phoneExpression));
        result.put("eventExpression", phoneExpression);
        result.put("authenticationSource", "HAR");
        return result;
    }

    static String templateIdFromLocation(String location) {
        if (isBlank(location)) {
            throw new ApiException(502,
                "i2message template registration returned no templateId in the Location header",
                TEMPLATE_PATH);
        }
        String trimmed = trimTrailingSlash(location.trim());
        int lastSlash = trimmed.lastIndexOf('/');
        String templateId = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
        if (isBlank(templateId)) {
            throw new ApiException(502,
                "i2message template registration returned no templateId in the Location header",
                TEMPLATE_PATH);
        }
        return templateId;
    }

    static String eventExpression(String eventDefinitionKey, String recipientNumberField) {
        return "{{Event." + eventDefinitionKey + "." + recipientNumberField + "}}";
    }

    private static Map<String, Object> buildRegistrationBody(
        String journeyId, String journeyName, int journeyVersion,
        String activityName, String messageName, String kakaoProfileId,
        String recipientNumberField, String content,
        boolean advertEnabled, boolean redeliveryEnabled
    ) {
        Map<String, Object> activityBasic = new LinkedHashMap<>();
        activityBasic.put("messageGroupType", "MC");
        activityBasic.put("agentVersion", "1.0.0");
        activityBasic.put("messageActivityName", activityName);
        activityBasic.put("messageGroupId", journeyId);
        activityBasic.put("messageGroupName", journeyName);
        activityBasic.put("messageGroupVersion", journeyVersion);
        activityBasic.put("messageGroupVersionId", journeyId);
        activityBasic.put("messageName", messageName);

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("kakaoProfile", kakaoProfileId);
        template.put("messageType", MESSAGE_TYPE_FRIENDTALK_TEXT);
        template.put("departmentId", "");
        template.put("recipientNumber", recipientNumberField);
        template.put("advert", Map.of("enabled", advertEnabled));
        template.put("redelivery", Map.of("enabled", redeliveryEnabled));
        template.put("adult", Map.of("enabled", false));
        template.put("additionalFields", List.of());
        template.put("encryptType", "NONE");
        template.put("content", content);
        template.put("buttons", List.of());
        template.put("coupon", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activityBasic", activityBasic);
        body.put("versionId", "");
        body.put("template", template);
        return body;
    }

    /** Builds the complete Journey REST activity JSON for the registered template. */
    private Map<String, Object> buildRestActivity(
        String journeyId, String activityName, String templateId, String phoneExpression
    ) {
        String receiveBase = receiveBaseUrl();

        Map<String, Object> inArgument = new LinkedHashMap<>();
        inArgument.put("messageType", MESSAGE_TYPE_FRIENDTALK_TEXT);
        inArgument.put("templateId", templateId);
        inArgument.put("personalizationField", Map.of("Phone", phoneExpression));
        inArgument.put("messageActivityId", "{{Activity.Id}}");
        inArgument.put("messageGroupId", journeyId);
        inArgument.put("messageDetailId", "{{Context.DefinitionInstanceId}}");

        Map<String, Object> execute = new LinkedHashMap<>();
        execute.put("timeout", 10000);
        execute.put("retryCount", 5);
        execute.put("retryDelay", 10000);
        execute.put("concurrentRequests", 10);
        execute.put("url", receiveBase + EXECUTE_PATH);
        execute.put("verb", "POST");
        execute.put("format", "json");
        execute.put("inArguments", List.of(inArgument));
        execute.put("outArguments", List.of());

        String publishValidateBody = JsonUtil.toJson(Map.of(
            "templateId", templateId,
            "messageType", MESSAGE_TYPE_FRIENDTALK_TEXT));

        Map<String, Object> publish = new LinkedHashMap<>();
        publish.put("url", receiveBase + PUBLISH_PATH);
        publish.put("useJwt", false);
        publish.put("verb", "POST");
        publish.put("body", publishValidateBody);

        Map<String, Object> validate = new LinkedHashMap<>();
        validate.put("url", receiveBase + VALIDATE_PATH);
        validate.put("useJwt", false);
        validate.put("verb", "POST");
        validate.put("body", publishValidateBody);

        Map<String, Object> configurationArguments = new LinkedHashMap<>();
        configurationArguments.put("applicationExtensionKey", APPLICATION_EXTENSION_KEY);
        configurationArguments.put("applicationExtensionId", APPLICATION_EXTENSION_ID);
        configurationArguments.put("publish", publish);
        configurationArguments.put("validate", validate);

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("key", "terminal");
        outcome.put("metaData", Map.of("label", "Done", "invalid", false));

        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("key", "REST-1");
        activity.put("name", activityName);
        activity.put("type", "REST");
        activity.put("outcomes", List.of(outcome));
        activity.put("arguments", Map.of("execute", execute));
        activity.put("configurationArguments", configurationArguments);
        activity.put("metaData", Map.of("isConfigured", true));
        return activity;
    }

    // ── Final journey/activity validation ────────────────────────────────────

    /**
     * Runs the final i2message validate-message call for the Journey's REST activity.
     * Must be called with the REST activity ID from the FINAL Journey GET, because
     * Marketing Cloud reassigns activity IDs on every Journey update.
     */
    public Map<String, Object> validateJourneyActivity(
        String templateId,
        String journeyId,
        String journeyKey,
        int journeyVersion,
        String activityObjectId,
        String harPath
    ) {
        requireJourneyKeyLength(journeyKey);

        HarAuth auth = extractHarAuth(resolveHarPath(harPath));
        String accessToken = login(auth);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateId", templateId);
        body.put("messageType", MESSAGE_TYPE_FRIENDTALK_TEXT);
        body.put("interactionVersion", String.valueOf(journeyVersion));
        body.put("activityObjectID", activityObjectId);
        body.put("interactionKey", journeyKey);
        body.put("interactionId", journeyId);
        body.put("originalDefinitionId", journeyId);

        ResponseEntity<Void> response;
        try {
            response = restClient.post()
                .uri(receiveBaseUrl() + VALIDATE_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                .body(body)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new ApiException(e.getStatusCode().value(),
                "i2message validate-message failed with HTTP " + e.getStatusCode().value()
                    + truncatedBody(e), VALIDATE_PATH);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("statusCode", response.getStatusCode().value());
        result.put("templateId", templateId);
        result.put("journeyId", journeyId);
        result.put("activityObjectId", activityObjectId);
        return result;
    }

    static void requireJourneyKeyLength(String journeyKey) {
        if (isBlank(journeyKey)) {
            throw new IllegalArgumentException("Missing required parameter: journeyKey");
        }
        if (journeyKey.length() > MAX_JOURNEY_KEY_LENGTH) {
            throw new IllegalArgumentException("journeyKey is " + journeyKey.length()
                + " characters; i2message requires at most " + MAX_JOURNEY_KEY_LENGTH
                + ". Recreate the Journey with a UUID key of 36 characters or fewer.");
        }
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    /** Response bodies from i2message errors never carry credentials, but keep them short. */
    private static String truncatedBody(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (isBlank(body)) return "";
        String compact = body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 300) compact = compact.substring(0, 300) + "...";
        return ": " + compact;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
