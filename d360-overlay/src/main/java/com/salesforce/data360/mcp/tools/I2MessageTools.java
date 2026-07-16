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
package com.salesforce.data360.mcp.tools;

import com.salesforce.data360.mcp.model.common.ApiException;
import com.salesforce.data360.mcp.service.I2MessageService;
import com.salesforce.data360.mcp.util.JsonUtil;
import com.salesforce.data360.mcp.util.ToolUtils;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * i2message (Kakao FriendTalk) tools for Marketing Cloud Journey Builder.
 *
 * <p>Authentication is fully automatic: fuel2token and MID are extracted from the
 * configured Marketing Cloud HAR export and exchanged for an i2message access token
 * internally. NEVER ask the user for fuel2token, MID, or accessToken in chat, and
 * never surface token values in any output.
 */
@Component
public class I2MessageTools {

    private final I2MessageService service;

    public I2MessageTools(I2MessageService service) {
        this.service = service;
    }

    @McpTool(
        name = "d360_i2message_register_friendtalk_template",
        description = """
            Register a Kakao FriendTalk (i2message) template for a Marketing Cloud Journey and \
            return the templateId plus a ready-to-insert Journey REST activity configuration. \
            Authentication is AUTOMATIC: Marketing Cloud fuel2token and MID are extracted from the \
            configured HAR export (I2MESSAGE_HAR_PATH or the harPath argument) and exchanged for an \
            i2message access token internally — never ask the user for tokens. Tokens are used in \
            memory only and never returned.

            Required Journey creation order:
            1. Create the Journey as a minimal Draft (key MUST be a UUID of 36 characters or fewer).
            2. Create a new EmailAudience Event Definition (schedule lives on the Event Definition).
            3. Connect the Journey trigger to the new Event Definition (id + eventDefinitionKey).
            4. Call THIS tool with the saved Journey ID/key/name/version.
            5. Add the returned activityConfiguration as the Journey's REST activity \
            (it already contains the new templateId, execute/publish/validate URLs, and phone mapping).
            6. Update the Journey.
            7. GET the Journey again to obtain the FINAL REST activity ID — Marketing Cloud \
            reassigns activity IDs on every update.
            8. Call d360_i2message_validate_journey_activity with that final activity ID.
            9. Report completion only after validation returns HTTP 200.

            WARNING: never validate with an activity ID captured before the last Journey update."""
    )
    public String registerFriendtalkTemplate(
        @McpToolParam(description = "Saved Journey ID (GUID). Used as the i2message messageGroupId.") String journeyId,
        @McpToolParam(description = "Journey key. Must be a UUID of 36 characters or fewer.") String journeyKey,
        @McpToolParam(description = "Journey name. Used as the i2message messageGroupName.") String journeyName,
        @McpToolParam(description = "Journey version number, e.g. 1.") Integer journeyVersion,
        @McpToolParam(description = "Kakao FriendTalk message body text (the advertisement content).") String content,
        @McpToolParam(description = "Event Definition key of the Journey's entry source. Used to build the phone personalization expression.") String eventDefinitionKey,
        @McpToolParam(description = "Message name shown in i2message. Defaults to '<journeyName> FriendTalk'.", required = false) String messageName,
        @McpToolParam(description = "Journey REST activity name. Defaults to 'i2message-stg'.", required = false) String activityName,
        @McpToolParam(description = "Kakao profile ID. Defaults to the current BU profile.", required = false) String kakaoProfileId,
        @McpToolParam(description = "Entry DE phone field name. Defaults to 'Phone'.", required = false) String recipientNumberField,
        @McpToolParam(description = "Mark the message as an advertisement. Defaults to true.", required = false) Boolean advertEnabled,
        @McpToolParam(description = "Enable SMS redelivery fallback. Defaults to true.", required = false) Boolean redeliveryEnabled,
        @McpToolParam(description = "Override the Marketing Cloud HAR file path. Defaults to I2MESSAGE_HAR_PATH or the built-in path.", required = false) String harPath
    ) {
        try {
            validateRequired("journeyId", journeyId);
            validateRequired("journeyKey", journeyKey);
            validateRequired("journeyName", journeyName);
            validateRequired("content", content);
            validateRequired("eventDefinitionKey", eventDefinitionKey);
            if (journeyVersion == null) {
                throw new IllegalArgumentException("Missing required parameter: journeyVersion");
            }

            String resolvedMessageName = isBlank(messageName)
                ? journeyName.trim() + " FriendTalk" : messageName.trim();
            String resolvedActivityName = isBlank(activityName)
                ? I2MessageService.DEFAULT_ACTIVITY_NAME : activityName.trim();
            String resolvedKakaoProfileId = isBlank(kakaoProfileId)
                ? I2MessageService.DEFAULT_KAKAO_PROFILE_ID : kakaoProfileId.trim();
            String resolvedRecipientField = isBlank(recipientNumberField)
                ? "Phone" : recipientNumberField.trim();

            return JsonUtil.toJson(service.registerFriendtalkTemplate(
                journeyId.trim(),
                journeyKey.trim(),
                journeyName.trim(),
                journeyVersion,
                resolvedActivityName,
                resolvedMessageName,
                resolvedKakaoProfileId,
                eventDefinitionKey.trim(),
                resolvedRecipientField,
                content,
                advertEnabled == null || advertEnabled,
                redeliveryEnabled == null || redeliveryEnabled,
                harPath
            ));
        } catch (IllegalArgumentException | ApiException e) {
            return ToolUtils.errorResponse(e);
        }
    }

    @McpTool(
        name = "d360_i2message_validate_journey_activity",
        description = """
            Run the FINAL i2message validate-message call for a Journey's FriendTalk REST activity. \
            Authentication is AUTOMATIC via the configured Marketing Cloud HAR export — never ask \
            the user for tokens. HTTP 200 with an empty body means the activity is valid; only then \
            may Journey completion be reported.

            Call order: update the Journey with the REST activity FIRST, then GET the Journey again \
            and pass the newly returned REST activity ID as activityObjectId. Marketing Cloud \
            reassigns activity IDs on every Journey update, so an activity ID captured before the \
            last update will fail validation. The Journey key must be a UUID of 36 characters or \
            fewer; longer keys are rejected before the API call."""
    )
    public String validateJourneyActivity(
        @McpToolParam(description = "templateId returned by d360_i2message_register_friendtalk_template.") String templateId,
        @McpToolParam(description = "Journey ID (GUID).") String journeyId,
        @McpToolParam(description = "Journey key (UUID, 36 characters or fewer).") String journeyKey,
        @McpToolParam(description = "Journey version number, e.g. 1.") Integer journeyVersion,
        @McpToolParam(description = "REST activity ID from the FINAL Journey GET (after the last update).") String activityObjectId,
        @McpToolParam(description = "Override the Marketing Cloud HAR file path. Defaults to I2MESSAGE_HAR_PATH or the built-in path.", required = false) String harPath
    ) {
        try {
            validateRequired("templateId", templateId);
            validateRequired("journeyId", journeyId);
            validateRequired("journeyKey", journeyKey);
            validateRequired("activityObjectId", activityObjectId);
            if (journeyVersion == null) {
                throw new IllegalArgumentException("Missing required parameter: journeyVersion");
            }

            return JsonUtil.toJson(service.validateJourneyActivity(
                templateId.trim(),
                journeyId.trim(),
                journeyKey.trim(),
                journeyVersion,
                activityObjectId.trim(),
                harPath
            ));
        } catch (IllegalArgumentException | ApiException e) {
            return ToolUtils.errorResponse(e);
        }
    }

    private static void validateRequired(String fieldName, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("Missing required parameter: " + fieldName);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
