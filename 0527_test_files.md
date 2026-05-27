# Manual Test Flow: Altibase + Qdrant

This document uses the user-facing API flow:

- `POST /api/v1/vector-tables`
- `POST /api/v1/vector-data/insert`
- `POST /api/v1/vector-data/update`
- `POST /api/v1/vector-data/delete`

It does not call `POST /api/v1/vector-payload-fields` directly. Payload field registration is done through `POST /api/v1/vector-tables`.

## 1. Prerequisites

Run the latest Altibase schema initialization script under the same database user that the service will connect with:

```text
src/main/resources/db/altibase/001_init_sidecar_altibase_epoch.sql
```

Start the service:

```bash
export SIDECAR_DB_URL='jdbc:Altibase://192.168.1.112:20300/mydb'
export SIDECAR_DB_USERNAME='VECTOR_ADMIN'
export SIDECAR_DB_PASSWORD='vector_admin'

export SIDECAR_QDRANT_ENABLED=true
export SIDECAR_QDRANT_URL='http://127.0.0.1:6333'
export SIDECAR_OUTBOX_WORKER_ENABLED=true

./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=altibase \
  -Dspring-boot.run.arguments='--server.port=8081'
```

The Qdrant environment variables must be set in the same terminal that starts Spring Boot. Setting them in another terminal after the service has started does not affect the running process.

Open another terminal:

```bash
export BASE='http://127.0.0.1:8081'
export QDRANT='http://127.0.0.1:6333'
export SCHEMA='VECTOR_ADMIN'
export TENANT='tenant_manual'
export TABLE="MANUAL_DOC_$(date +%s)"
export VECTOR_COLUMN='EMBEDDING'
export COLLECTION="${TABLE}_${VECTOR_COLUMN}_V1"
export ALIAS="${TABLE}_${VECTOR_COLUMN}_ACTIVE"
```

## 2. Create A Vector Table

This creates the Altibase business table, registers the vector column, provisions the Qdrant collection and alias, and registers the payload mapping `DOC_TYPE -> docType`.

```bash
CREATE_RESP=$(curl -sS -X POST "$BASE/api/v1/vector-tables" \
  -H 'Content-Type: application/json' \
  -d "{
    \"tenantId\": \"$TENANT\",
    \"schemaName\": \"$SCHEMA\",
    \"tableName\": \"$TABLE\",
    \"primaryKey\": {
      \"name\": \"id\",
      \"type\": \"bigint\"
    },
    \"scalarColumns\": [
      {
        \"name\": \"DOC_TYPE\",
        \"type\": \"varchar\",
        \"length\": 50,
        \"nullable\": true,
        \"payloadKey\": \"docType\",
        \"payloadSyncEnabled\": true,
        \"payloadFieldType\": \"KEYWORD\"
      }
    ],
    \"vectorColumn\": {
      \"name\": \"$VECTOR_COLUMN\",
      \"dimension\": 3,
      \"nullable\": true
    }
  }")

echo "$CREATE_RESP"
```

If `jq` is available:

```bash
export COLUMN_ID=$(printf '%s' "$CREATE_RESP" | jq -r '.data.columnId')
echo "$COLUMN_ID"
```

Without `jq`, copy `data.columnId` manually from the response:

```bash
export COLUMN_ID=<actual-column-id>
```

Check Qdrant:

```bash
curl -sS "$QDRANT/collections/$COLLECTION"
curl -sS "$QDRANT/aliases"
```

Check Altibase metadata:

```sql
SELECT * FROM SYS_VECTOR_COLUMNS_ WHERE COLUMN_ID = <COLUMN_ID>;
SELECT * FROM SYS_VECTOR_COLLECTIONS_ WHERE COLUMN_ID = <COLUMN_ID>;
SELECT * FROM SYS_VECTOR_INDEXES_ WHERE COLUMN_ID = <COLUMN_ID>;
SELECT * FROM SYS_VECTOR_PAYLOAD_FIELDS_ WHERE COLUMN_ID = <COLUMN_ID>;
```

Check sidecar collection metadata:

```bash
curl -sS "$BASE/api/v1/vector-collections?columnId=$COLUMN_ID"
```

Expected collection state:

```text
servingState = ACTIVE
collectionStatus = READY
```

If the collection stays in `BUILDING/CREATING`, the most common cause is that the service was started without `SIDECAR_QDRANT_ENABLED=true`. Restart the service with the Qdrant variables in the same command or same terminal, then run the create-table step again.

## 3. Insert Data

```bash
curl -sS -X POST "$BASE/api/v1/vector-data/insert" \
  -H 'Content-Type: application/json' \
  -d "{
    \"tenantId\": \"$TENANT\",
    \"schemaName\": \"$SCHEMA\",
    \"tableName\": \"$TABLE\",
    \"vectorColumn\": \"$VECTOR_COLUMN\",
    \"pk\": 1001,
    \"vector\": [0.1, 0.2, 0.3],
    \"payload\": {
      \"docType\": \"alpha\"
    }
  }"
```

Wait for the outbox worker:

```bash
sleep 2
```

Check outbox events:

```bash
curl -sS "$BASE/api/v1/vector-outbox-events?columnId=$COLUMN_ID&limit=10"
```

Check the Altibase business row:

```sql
SELECT * FROM VECTOR_ADMIN.MANUAL_DOC_xxx;

SELECT EVENT_ID, EVENT_STATUS, SOURCE_PK, SOURCE_VERSION
FROM SYS_VECTOR_OUTBOX_EVENTS_
WHERE COLUMN_ID = <COLUMN_ID>
ORDER BY EVENT_ID;
```

Check the Qdrant point:

```bash
curl -sS -X POST "$QDRANT/collections/$COLLECTION/points" \
  -H 'Content-Type: application/json' \
  -d '{
    "ids": [1001],
    "with_payload": true,
    "with_vector": true
  }'
```

Expected payload:

```json
{
  "docType": "alpha",
  "_sidecar_source_version": 1
}
```

## 4. Update Data

```bash
curl -sS -X POST "$BASE/api/v1/vector-data/update" \
  -H 'Content-Type: application/json' \
  -d "{
    \"tenantId\": \"$TENANT\",
    \"schemaName\": \"$SCHEMA\",
    \"tableName\": \"$TABLE\",
    \"vectorColumn\": \"$VECTOR_COLUMN\",
    \"pk\": 1001,
    \"vector\": [0.7, 0.8, 0.9],
    \"payload\": {
      \"docType\": \"beta\"
    }
  }"
```

Wait for the outbox worker:

```bash
sleep 2
```

Check the Qdrant point:

```bash
curl -sS -X POST "$QDRANT/collections/$COLLECTION/points" \
  -H 'Content-Type: application/json' \
  -d '{
    "ids": [1001],
    "with_payload": true,
    "with_vector": true
  }'
```

Expected payload:

```json
{
  "docType": "beta",
  "_sidecar_source_version": 2
}
```

Check Altibase:

```sql
SELECT ID, ROW_VERSION, DOC_TYPE, EMBEDDING
FROM VECTOR_ADMIN.MANUAL_DOC_xxx
WHERE ID = 1001;
```

Expected:

```text
DOC_TYPE = beta
ROW_VERSION = 2
```

## 5. Delete Data

```bash
curl -sS -X POST "$BASE/api/v1/vector-data/delete" \
  -H 'Content-Type: application/json' \
  -d "{
    \"tenantId\": \"$TENANT\",
    \"schemaName\": \"$SCHEMA\",
    \"tableName\": \"$TABLE\",
    \"vectorColumn\": \"$VECTOR_COLUMN\",
    \"pk\": 1001
  }"
```

Wait for the outbox worker:

```bash
sleep 2
```

Check Altibase:

```sql
SELECT COUNT(*)
FROM VECTOR_ADMIN.MANUAL_DOC_xxx
WHERE ID = 1001;
```

Expected count:

```text
0
```

Check Qdrant:

```bash
curl -sS -X POST "$QDRANT/collections/$COLLECTION/points" \
  -H 'Content-Type: application/json' \
  -d '{
    "ids": [1001],
    "with_payload": true,
    "with_vector": true
  }'
```

Expected `result`:

```json
[]
```

## 6. Manual Cleanup

Clean up after inspecting the test resources.

Altibase:

```sql
DROP TABLE VECTOR_ADMIN.MANUAL_DOC_xxx;

DELETE FROM SYS_VECTOR_SYNC_ERRORS_ WHERE COLUMN_ID = <COLUMN_ID>;
DELETE FROM SYS_VECTOR_SYNC_PROGRESS_ WHERE COLUMN_ID = <COLUMN_ID>;
DELETE FROM SYS_VECTOR_SYNC_JOBS_ WHERE COLUMN_ID = <COLUMN_ID>;
DELETE FROM SYS_VECTOR_PAYLOAD_FIELDS_ WHERE COLUMN_ID = <COLUMN_ID>;
DELETE FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE COLUMN_ID = <COLUMN_ID>;
DELETE FROM SYS_VECTOR_SOURCE_VERSIONS_ WHERE COLUMN_ID = <COLUMN_ID>;
DELETE FROM SYS_VECTOR_INDEXES_ WHERE COLUMN_ID = <COLUMN_ID>;
DELETE FROM SYS_VECTOR_COLLECTIONS_ WHERE COLUMN_ID = <COLUMN_ID>;
DELETE FROM SYS_VECTOR_COLUMNS_ WHERE COLUMN_ID = <COLUMN_ID>;
```

Qdrant:

```bash
curl -sS -X DELETE "$QDRANT/collections/$COLLECTION"
```
