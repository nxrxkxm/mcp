# Claude GLOW Journey + i2message Creation Instructions

Use these rules whenever creating a Salesforce Marketing Cloud Journey for GLOW with a Data Extension entry source, schedule, and i2message Kakao FriendTalk activity.

## Non-negotiable rules

1. Create and save the Journey as `Draft`. Never activate or publish unless the user explicitly requests it.
2. Use a Journey `key` that is a UUID and no longer than 36 characters. A longer key can make i2message validation fail.
3. Create a new Event Definition for the new Journey. Do not reuse an Event Definition or automation from a deleted, test, or unrelated Journey.
4. Define the schedule on the Event Definition, not on the Journey trigger.
5. After creating the Event Definition, connect the Journey trigger with its returned `id` and `eventDefinitionKey`.
6. Use the same Event Definition key in all Journey event expressions, including `defaults.mobileNumber` and the i2message phone mapping.
7. Register a new i2message template using the saved Journey ID. Do not copy a template ID from another Journey.
8. Preserve activity keys, trigger keys, IDs, and `outcomes` when updating an existing Journey.
9. After every Journey update, GET the Journey again because Marketing Cloud can assign new activity IDs.
10. Validate i2message with the final activity ID and full Journey context. HTTP 200 is required before reporting success.

## Required creation order

1. Resolve the exact entry Data Extension and inspect its fields.
2. Confirm the subscriber/contact key and phone field, normally `Phone`.
3. Create the Journey as a minimal Draft and obtain:
   - Journey ID
   - Journey key
   - Journey version
4. Create a new EmailAudience Event Definition using the selected Data Extension and schedule mode.
5. Update the Journey trigger to reference the new Event Definition.
6. Register the i2message FriendTalk template using the saved Journey ID.
7. Add or update the REST activity using the returned i2message template ID.
8. GET the Journey again and obtain the final REST activity ID.
9. Validate i2message using the final activity and Journey context.
10. GET the Event Definition, automation, and Journey for final verification.

## Event Definition base payload

Use the current BU's EmailAudience application extension values. For this verified BU, the UI-compatible payload uses:

```json
{
  "isVisibleInPicker": false,
  "type": "EmailAudience",
  "iconUrl": "/images/icon-data-extension.svg",
  "sourceApplicationExtensionId": "A62FF9DF-DC1C-4E9A-87CD-C05841D79F38",
  "entrySourceGroupConfigUrl": "jb:///data/entry/audience/entrysourcegroupconfig.json",
  "dataExtensionId": "<DATA_EXTENSION_ID>",
  "name": "<JOURNEY_NAME>",
  "mode": 1,
  "eventDefinitionKey": "DEAudience-<UNIQUE_VALUE>",
  "category": "Audience",
  "metaData": {
    "criteriaDescription": null,
    "scheduleFlowMode": "recurring"
  },
  "arguments": {
    "useHighWatermark": true,
    "resetHighWatermark": false
  },
  "configurationArguments": {
    "unconfigured": false
  }
}
```

The Event Definition key must be unique and must not contain spaces.

## Recurring schedule

For a recurring schedule, include a `schedule` object and set:

```json
{
  "schedule": {
    "startDateTime": "2026-07-15T18:00",
    "timeZone": "Korea Standard Time",
    "endType": "EndDate",
    "frequency": "Daily",
    "recurrencePattern": "Interval",
    "interval": 1,
    "endDateTime": "2026-07-31T00:00:00"
  },
  "metaData": {
    "criteriaDescription": null,
    "scheduleFlowMode": "recurring"
  }
}
```

Important:

- When the user specifies KST, send the local wall-clock value in `startDateTime` and explicitly set `timeZone` to `Korea Standard Time`.
- Do not convert the requested KST time to UTC or Central Time for this BU.
- Verify the returned Event Definition still contains `timeZone: Korea Standard Time` and the requested local start time.
- A Draft Journey's generated automation normally has `PausedSchedule`. This is expected until activation.

## On Activation / run once

For "On Activation", do not include a recurring `schedule` object. Use:

```json
{
  "metaData": {
    "criteriaDescription": null,
    "scheduleFlowMode": "runOnce",
    "runOnceScheduleMode": "onPublish"
  },
  "arguments": {
    "useHighWatermark": false
  },
  "configurationArguments": {
    "unconfigured": false
  }
}
```

Interpret user language as follows:

- "정기 발송", "반복", "Recurring" -> `scheduleFlowMode: recurring`
- "활성화 시 1회", "On Activation", "Once" -> `scheduleFlowMode: runOnce` and `runOnceScheduleMode: onPublish`

## Journey trigger after Event Definition creation

Use the Event Definition returned by the creation request:

```json
{
  "key": "TRIGGER",
  "name": "TRIGGER",
  "type": "EmailAudience",
  "outcomes": [],
  "arguments": {},
  "configurationArguments": {},
  "metaData": {
    "sourceInteractionId": "00000000-0000-0000-0000-000000000000",
    "eventDefinitionId": "<EVENT_DEFINITION_ID>",
    "eventDefinitionKey": "<EVENT_DEFINITION_KEY>",
    "chainType": "none",
    "configurationRequired": false,
    "iconUrl": "/images/icon-data-extension.svg",
    "title": "Data Extension",
    "entrySourceGroupConfigUrl": "jb:///data/entry/audience/entrysourcegroupconfig.json"
  }
}
```

Do not attach an old automation ID to the new Journey trigger. The Event Definition creation response creates and owns its automation. Verify the Event Definition's `automationId` and GET that automation separately.

For one entry per contact, set:

```json
{
  "entryMode": "OnceAndDone"
}
```

Phone default example:

```json
{
  "defaults": {
    "mobileNumber": [
      "{{Event.<EVENT_DEFINITION_KEY>.\"Phone\"}}"
    ]
  }
}
```

## i2message authentication and template registration

i2message template registration is separate from the Journey REST activity. Register the template first.

Authentication flow used by the installed i2message UI:

1. Obtain the current Marketing Cloud `fuel2token` and MID/organization ID from the authenticated UI context.
2. POST form data to:
   `https://stg.mc.api.i2message.io/v1/accounts/login`
3. Form fields:
   - `fuelapiRestHost=<CURRENT_BU_REST_HOST>`
   - `fuel2token=<CURRENT_FUEL2TOKEN>`
   - `mid=<CURRENT_ORGANIZATION_ID>`
4. Use the returned `accessToken` as `Authorization: Bearer <TOKEN>`.

Never store or paste an expired fuel2token into project instructions. Obtain it from the current authenticated session.

Register the FriendTalk template:

`POST https://stg.mc.api.i2message.io/v1/template`

```json
{
  "activityBasic": {
    "messageGroupType": "MC",
    "agentVersion": "1.0.0",
    "messageActivityName": "i2message-stg",
    "messageGroupId": "<JOURNEY_ID>",
    "messageGroupName": "<JOURNEY_NAME>",
    "messageGroupVersion": 1,
    "messageGroupVersionId": "<JOURNEY_ID>",
    "messageName": "<MESSAGE_NAME>"
  },
  "versionId": "",
  "template": {
    "kakaoProfile": "<CURRENT_BU_KAKAO_PROFILE_ID>",
    "messageType": "FRIENDTALK_TEXT",
    "departmentId": "",
    "recipientNumber": "Phone",
    "advert": { "enabled": true },
    "redelivery": { "enabled": true },
    "adult": { "enabled": false },
    "additionalFields": [],
    "encryptType": "NONE",
    "content": "<MESSAGE_CONTENT>",
    "buttons": [],
    "coupon": null
  }
}
```

Expected result:

- HTTP 201
- The new i2message `templateId` is returned in the `Location` response header.
- An empty response body is normal.

## i2message REST activity

Verified current-BU values:

- Type: `REST`
- Activity name: `i2message-stg`
- Application extension key: `5a170bea-dc51-42f2-9c49-338070b7282d`
- Execute: `https://stg.mc.receive.i2message.io/v1/journey/execute-message`
- Publish: `https://stg.mc.receive.i2message.io/v1/journey/publish-message`
- Validate: `https://stg.mc.receive.i2message.io/v1/journey/validate-message`

Core activity configuration:

```json
{
  "key": "REST-1",
  "name": "i2message-stg",
  "type": "REST",
  "outcomes": [
    {
      "key": "terminal",
      "metaData": {
        "label": "Done",
        "invalid": false
      }
    }
  ],
  "arguments": {
    "execute": {
      "timeout": 10000,
      "retryCount": 5,
      "retryDelay": 10000,
      "concurrentRequests": 10,
      "url": "https://stg.mc.receive.i2message.io/v1/journey/execute-message",
      "verb": "POST",
      "format": "json",
      "inArguments": [
        {
          "messageType": "FRIENDTALK_TEXT",
          "templateId": "<NEW_TEMPLATE_ID>",
          "personalizationField": {
            "Phone": "{{Event.<EVENT_DEFINITION_KEY>.Phone}}"
          },
          "messageActivityId": "{{Activity.Id}}",
          "messageGroupId": "<JOURNEY_ID>",
          "messageDetailId": "{{Context.DefinitionInstanceId}}"
        }
      ],
      "outArguments": []
    }
  },
  "configurationArguments": {
    "applicationExtensionKey": "5a170bea-dc51-42f2-9c49-338070b7282d",
    "publish": {
      "url": "https://stg.mc.receive.i2message.io/v1/journey/publish-message",
      "useJwt": false,
      "verb": "POST",
      "body": "{\"templateId\":\"<NEW_TEMPLATE_ID>\",\"messageType\":\"FRIENDTALK_TEXT\"}"
    },
    "validate": {
      "url": "https://stg.mc.receive.i2message.io/v1/journey/validate-message",
      "useJwt": false,
      "verb": "POST",
      "body": "{\"templateId\":\"<NEW_TEMPLATE_ID>\",\"messageType\":\"FRIENDTALK_TEXT\"}"
    }
  },
  "metaData": {
    "isConfigured": true
  }
}
```

## i2message final validation

After the final Journey update, GET the Journey and use the newly returned REST activity ID.

`POST https://stg.mc.receive.i2message.io/v1/journey/validate-message`

```json
{
  "templateId": "<NEW_TEMPLATE_ID>",
  "messageType": "FRIENDTALK_TEXT",
  "interactionVersion": "<JOURNEY_VERSION>",
  "activityObjectID": "<FINAL_REST_ACTIVITY_ID>",
  "interactionKey": "<JOURNEY_KEY_MAX_36_CHARS>",
  "interactionId": "<JOURNEY_ID>",
  "originalDefinitionId": "<JOURNEY_ID>"
}
```

HTTP 200 with an empty body is a successful validation.

## Final verification checklist

Before reporting completion, verify all of the following:

- Journey status is `Draft` unless activation was explicitly requested.
- Journey key length is at most 36 characters.
- Entry DE ID is correct.
- Event Definition belongs to the new Journey and has `interactionCount: 1` after linking.
- Recurring schedule has the requested local time and `timeZone: Korea Standard Time`.
- Generated automation points its Fire Event activity to the new Event Definition ID.
- `entryMode` is `OnceAndDone` when repeat entry is prohibited.
- Journey trigger references the new Event Definition ID and key.
- `defaults.mobileNumber` uses the new Event Definition key.
- i2message phone personalization uses the same new Event Definition key.
- i2message template ID came from the new registration `Location` header.
- REST activity has `metaData.isConfigured: true`.
- REST activity `outcomes` remain connected.
- Final i2message validate call returns HTTP 200.
- Do not claim success based only on the Journey card display.

## User-facing completion response

Keep the final response concise and include:

- Journey name and version
- Draft/active status
- Entry Data Extension
- Schedule mode, local time, and time zone
- i2message message type and validation result
- Direct Journey Builder link

