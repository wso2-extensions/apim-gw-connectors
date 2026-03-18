## Kong Control Plane Connector

This module contains the Kong control-plane connector implementation used by WSO2 API Manager
for gateway federation and federated API-key operations.

## Federated API Key Flow

Kong integration uses the following model:

1. API key creation:
   - Create a Kong Consumer per WSO2 API key.
   - Create a Kong `key-auth` credential using the WSO2-generated key value.
   - Add API ACL group: `api_<remoteApiId>`.
2. API key association:
   - Remove existing `tier_*` ACL groups from the consumer.
   - Remove existing `sub_<remoteApiId>_*` ACL groups from the consumer.
   - Add tier ACL group: `tier_<remotePlanId>`.
   - Add subscription ACL group: `sub_<remoteApiId>_<remotePlanId>`.
   - Remove existing consumer-group memberships from the consumer.
   - Add consumer to mapped remote consumer group (group ID from plan mapping).
3. API key dissociation:
   - Remove `tier_*` ACL groups.
   - Remove `sub_<remoteApiId>_*` ACL groups.
   - Keep `api_<remoteApiId>` ACL group.
   - Remove consumer from all consumer groups.
4. API key revoke/delete:
   - Delete the Kong consumer.

## Required Kong-side Runtime Setup

You can choose one of the following ACL strategies per API/service.

### Option 1: API-boundness only

Attach ACL plugin and allow:

- `api_<remoteApiId>`

Result: key is bound to API; association state is not required for invocation.

### Option 2: Subscription validation only

Attach ACL plugin and allow:

- `tier_<remotePlanId>`

Result: invocation requires an associated tier; not API-scoped by ACL itself.

### Option 3: Both API-boundness and subscription validation

Attach ACL plugin and allow:

- `sub_<remoteApiId>_<remotePlanId>`

Result: invocation requires both API and plan to match.

## Admin Plan Mapping Notes

- Remote plan mapping uses Kong consumer groups.
- In WSO2 Admin portal, map local WSO2 plans to remote **consumer group IDs**.
- Connector derives ACL groups as:
  - `tier_<mappedConsumerGroupId>`
  - `sub_<remoteApiId>_<mappedConsumerGroupId>`
- "Fetch Remote Plans" returns available Kong consumer groups for selection.
