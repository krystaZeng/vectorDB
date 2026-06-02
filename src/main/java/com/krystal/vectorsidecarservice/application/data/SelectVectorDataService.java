package com.krystal.vectorsidecarservice.application.data;

import com.krystal.vectorsidecarservice.application.port.in.SelectVectorDataUseCase;
import com.krystal.vectorsidecarservice.application.port.out.RelationalSchemaPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorDataRelationalPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorEngineDataPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorPayloadFieldPort;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorColumnLifecycle;
import com.krystal.vectorsidecarservice.application.system.VectorEngineDataRouter;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorPayloadFieldMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SelectVectorDataService implements SelectVectorDataUseCase {

    private static final String DEFAULT_TENANT = "DEFAULT";
    private static final String DEFAULT_SCHEMA = "PUBLIC";
    private static final String ROW_VERSION_COLUMN = "ROW_VERSION";
    private static final String VECTOR_INDEX_VERSION_COLUMN = "VECTOR_INDEX_VERSION";
    private static final String SIDECAR_COLUMN_ID_PAYLOAD_KEY = "_sidecar_column_id";
    private static final String SIDECAR_SOURCE_PK_PAYLOAD_KEY = "_sidecar_source_pk";
    private static final String SIDECAR_PK_VALUE_TYPE_PAYLOAD_KEY = "_sidecar_pk_value_type";
    private static final String VECTOR_INDEX_VERSION_PAYLOAD_KEY = "_sidecar_vector_index_version";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 100;
    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final int MAX_CANDIDATE_LIMIT = 200;
    private static final int MAX_FILTER_IN_VALUES = 100;
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final VectorMetadataPort vectorMetadataPort;
    private final VectorCollectionPort vectorCollectionPort;
    private final VectorPayloadFieldPort vectorPayloadFieldPort;
    private final RelationalSchemaPort relationalSchemaPort;
    private final VectorDataRelationalPort vectorDataRelationalPort;
    private final VectorEngineDataRouter vectorEngineDataRouter;
    private final VectorValueEncoder vectorValueEncoder;
    private final VectorPointIdNormalizer pointIdNormalizer;

    @Override
    public SelectVectorDataResult select(SelectVectorDataCommand command) {
        if (command == null) {
            throw new BizException("request must not be null");
        }
        String tenantId = normalizeWithDefault(command.tenantId(), DEFAULT_TENANT);
        String schemaName = normalizeIdentifier(command.schemaName(), "schemaName", DEFAULT_SCHEMA);
        String tableName = normalizeIdentifier(command.tableName(), "tableName", null);
        String vectorColumnName = optionalIdentifier(command.vectorColumn(), "vectorColumn");
        VectorColumnMeta column = resolveColumn(tenantId, schemaName, tableName, vectorColumnName);
        if (!relationalSchemaPort.tableExists(column.schemaName(), column.tableName())) {
            throw new BizException("table does not exist: " + column.schemaName() + "." + column.tableName());
        }
        List<VectorPayloadFieldMeta> payloadFields = vectorPayloadFieldPort.findByColumnId(column.columnId());
        List<String> projection = projection(command.select(), column, payloadFields);
        if (command.nearest() == null) {
            return relationalOnly(command, column, payloadFields, projection);
        }
        return vectorFirst(command, column, payloadFields, projection);
    }

    private SelectVectorDataResult relationalOnly(
            SelectVectorDataCommand command,
            VectorColumnMeta column,
            List<VectorPayloadFieldMeta> payloadFields,
            List<String> projection
    ) {
        int limit = limit(command.limit(), DEFAULT_LIMIT, MAX_LIMIT, "limit");
        int offset = command.offset() == null ? 0 : command.offset();
        if (offset < 0) {
            throw new BizException("offset must not be negative");
        }
        List<VectorDataRelationalPort.RelationalRow> rows = vectorDataRelationalPort.queryRows(
                new VectorDataRelationalPort.QueryRowsCommand(
                        column.schemaName(),
                        column.tableName(),
                        column.pkColumn(),
                        projection,
                        relationalConditions(command.where(), column, payloadFields),
                        relationalOrderBy(command.orderBy(), column, payloadFields),
                        limit,
                        offset
                )
        );
        List<SelectRowResult> results = rows.stream()
                .map(row -> new SelectRowResult(row.pk(), null, null, row.values()))
                .toList();
        return new SelectVectorDataResult(
                "RELATIONAL_ONLY",
                "RELATIONAL_STRONG_READ",
                results,
                new SelectDiagnostics(0, rows.size(), 0, 0, 0, results.size())
        );
    }

    private SelectVectorDataResult vectorFirst(
            SelectVectorDataCommand command,
            VectorColumnMeta column,
            List<VectorPayloadFieldMeta> payloadFields,
            List<String> projection
    ) {
        if (command.offset() != null && command.offset() != 0) {
            throw new BizException("offset is not supported for vector search");
        }
        if (command.orderBy() != null && !command.orderBy().isEmpty()) {
            throw new BizException("orderBy is not supported for vector search");
        }
        if (!VectorColumnLifecycle.ACTIVE.status().equals(column.status())) {
            throw new BizException("vector column is not ACTIVE: " + column.columnId());
        }
        String vectorIndexVersionColumn = relationalSchemaPort.columnExists(
                column.schemaName(),
                column.tableName(),
                VECTOR_INDEX_VERSION_COLUMN
        ) ? VECTOR_INDEX_VERSION_COLUMN : null;
        if (vectorIndexVersionColumn == null) {
            throw new BizException("VECTOR_INDEX_VERSION_MISSING: " + column.schemaName() + "." + column.tableName());
        }
        VectorCollectionMeta collection = readyCollection(column.columnId());
        int topK = limit(command.nearest().topK(), DEFAULT_TOP_K, MAX_TOP_K, "nearest.topK");
        int candidateLimit = Math.min(topK * CANDIDATE_MULTIPLIER, MAX_CANDIDATE_LIMIT);
        VectorEngineDataPort.SearchPointsResult searchResult = vectorEngineDataRouter.get(collection.engineType())
                .searchPoints(new VectorEngineDataPort.SearchPointsCommand(
                        readTargetName(collection),
                        collection.qdrantVectorName(),
                        vectorValueEncoder.toFloatVector(command.nearest().vector(), column.dimension()),
                        candidateLimit,
                        command.nearest().scoreThreshold(),
                        vectorFilters(command.where(), payloadFields),
                        true
                ));
        List<ParsedHit> parsedHits = parseHits(searchResult.hits(), column);
        List<Object> pkValues = parsedHits.stream()
                .map(ParsedHit::pkValue)
                .distinct()
                .toList();
        List<VectorDataRelationalPort.RelationalRow> relationalRows = vectorDataRelationalPort.findRowsByPks(
                new VectorDataRelationalPort.FindRowsByPksCommand(
                        column.schemaName(),
                        column.tableName(),
                        column.pkColumn(),
                        pkValues,
                        projection,
                        vectorIndexVersionColumn
                )
        );
        Map<String, VectorDataRelationalPort.RelationalRow> rowsByPk = relationalRows.stream()
                .collect(Collectors.toMap(
                        row -> pointIdNormalizer.relationalPkText(row.pk()),
                        row -> row,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        int staleDeleted = 0;
        int staleVersion = 0;
        List<SelectRowResult> results = new ArrayList<>();
        for (ParsedHit hit : parsedHits) {
            VectorDataRelationalPort.RelationalRow row = rowsByPk.get(hit.sourcePk());
            if (row == null) {
                staleDeleted++;
                continue;
            }
            if (row.vectorIndexVersion() == null || !row.vectorIndexVersion().equals(hit.vectorIndexVersion())) {
                staleVersion++;
                continue;
            }
            results.add(new SelectRowResult(row.pk(), hit.score(), row.vectorIndexVersion(), row.values()));
            if (results.size() >= topK) {
                break;
            }
        }

        return new SelectVectorDataResult(
                "VECTOR_FIRST",
                "EVENTUAL_VECTOR_INDEX_STRICT",
                results,
                new SelectDiagnostics(
                        searchResult.hits().size(),
                        relationalRows.size(),
                        staleDeleted,
                        staleVersion,
                        0,
                        results.size()
                )
        );
    }

    private VectorColumnMeta resolveColumn(String tenantId, String schemaName, String tableName, String vectorColumnName) {
        if (vectorColumnName != null) {
            return vectorMetadataPort.findByIdentity(tenantId, schemaName, tableName, vectorColumnName)
                    .orElseThrow(() -> new BizException("vector column metadata not found: "
                            + tenantId + "." + schemaName + "." + tableName + "." + vectorColumnName));
        }
        List<VectorColumnMeta> columns = vectorMetadataPort.findByTableIdentity(tenantId, schemaName, tableName);
        if (columns.isEmpty()) {
            throw new BizException("vector table metadata not found: " + tenantId + "." + schemaName + "." + tableName);
        }
        if (columns.size() > 1) {
            throw new BizException("vectorColumn is required when table has multiple vector columns: "
                    + tenantId + "." + schemaName + "." + tableName);
        }
        return columns.get(0);
    }

    private List<String> projection(
            List<String> requested,
            VectorColumnMeta column,
            List<VectorPayloadFieldMeta> payloadFields
    ) {
        List<String> raw = requested == null || requested.isEmpty()
                ? defaultProjection(column, payloadFields)
                : requested;
        List<String> projection = new ArrayList<>();
        for (String field : raw) {
            String sourceColumn = sourceColumnOrIdentifier(field, payloadFields, "select field");
            validateReturnColumn(sourceColumn, column);
            if (!projection.contains(sourceColumn)) {
                projection.add(sourceColumn);
            }
        }
        return projection;
    }

    private List<String> defaultProjection(VectorColumnMeta column, List<VectorPayloadFieldMeta> payloadFields) {
        List<String> projection = new ArrayList<>();
        projection.add(column.pkColumn());
        payloadFields.stream()
                .filter(field -> "ACTIVE".equals(field.fieldStatus()))
                .filter(field -> "Y".equals(field.isReturnable()))
                .map(VectorPayloadFieldMeta::sourceColumnName)
                .map(value -> normalizeIdentifier(value, "payload sourceColumnName", null))
                .forEach(sourceColumn -> {
                    if (!projection.contains(sourceColumn)) {
                        projection.add(sourceColumn);
                    }
                });
        return projection;
    }

    private List<VectorDataRelationalPort.Condition> relationalConditions(
            List<SelectCondition> conditions,
            VectorColumnMeta column,
            List<VectorPayloadFieldMeta> payloadFields
    ) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }
        return conditions.stream()
                .map(condition -> {
                    String sourceColumn = sourceColumnOrIdentifier(condition.field(), payloadFields, "where field");
                    validateReturnColumn(sourceColumn, column);
                    return new VectorDataRelationalPort.Condition(
                            sourceColumn,
                            normalizeOp(condition.op()),
                            condition.value(),
                            condition.values()
                    );
                })
                .toList();
    }

    private List<VectorDataRelationalPort.OrderBy> relationalOrderBy(
            List<OrderBy> orderBy,
            VectorColumnMeta column,
            List<VectorPayloadFieldMeta> payloadFields
    ) {
        if (orderBy == null || orderBy.isEmpty()) {
            return List.of();
        }
        return orderBy.stream()
                .map(order -> {
                    String sourceColumn = sourceColumnOrIdentifier(order.field(), payloadFields, "orderBy field");
                    validateReturnColumn(sourceColumn, column);
                    return new VectorDataRelationalPort.OrderBy(sourceColumn, order.direction());
                })
                .toList();
    }

    private List<VectorEngineDataPort.SearchFilterCondition> vectorFilters(
            List<SelectCondition> conditions,
            List<VectorPayloadFieldMeta> payloadFields
    ) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }
        return conditions.stream()
                .map(condition -> {
                    VectorPayloadFieldMeta field = payloadField(condition.field(), payloadFields)
                            .orElseThrow(() -> new BizException("FILTER_NOT_PUSHABLE: field is not a registered payload field: "
                                    + condition.field()));
                    requirePushable(field);
                    PayloadFilterType filterType = normalizePayloadFilterType(field);
                    String op = normalizeOp(condition.op());
                    validateVectorFilterOp(filterType, op, field);
                    NormalizedFilterValue value = normalizeFilterValue(filterType, op, condition, field);
                    return new VectorEngineDataPort.SearchFilterCondition(
                            field.payloadKey(),
                            op,
                            value.value(),
                            value.values(),
                            filterType.name()
                    );
                })
                .toList();
    }

    private void requirePushable(VectorPayloadFieldMeta field) {
        if (!"ACTIVE".equals(field.fieldStatus())
                || !"Y".equals(field.syncEnabled())
                || !"Y".equals(field.isFilterable())
                || !"Y".equals(field.isIndexed())
                || !"CREATED".equals(field.payloadIndexStatus())) {
            throw new BizException("FILTER_NOT_PUSHABLE: field "
                    + field.sourceColumnName()
                    + " is not backed by a ready Qdrant payload index");
        }
    }

    private PayloadFilterType normalizePayloadFilterType(VectorPayloadFieldMeta field) {
        String fieldType = field.fieldType();
        if (fieldType == null || fieldType.isBlank()) {
            throw new BizException("FILTER_TYPE_NOT_SUPPORTED: field "
                    + field.sourceColumnName()
                    + " type is not supported for vector filter");
        }
        String normalized = fieldType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "VARCHAR", "CHAR", "STRING", "KEYWORD" -> PayloadFilterType.STRING;
            case "INTEGER", "INT" -> PayloadFilterType.INTEGER;
            case "BIGINT", "LONG" -> PayloadFilterType.LONG;
            case "DOUBLE", "FLOAT" -> PayloadFilterType.DOUBLE;
            case "BOOLEAN", "BOOL" -> PayloadFilterType.BOOLEAN;
            default -> throw new BizException("FILTER_TYPE_NOT_SUPPORTED: field "
                    + field.sourceColumnName()
                    + " type "
                    + normalized
                    + " is not supported for vector filter");
        };
    }

    private void validateVectorFilterOp(PayloadFilterType type, String op, VectorPayloadFieldMeta field) {
        boolean supported = switch (type) {
            case STRING -> op.equals("EQ") || op.equals("IN");
            case INTEGER, LONG, DOUBLE -> op.equals("EQ")
                    || op.equals("IN")
                    || op.equals("GT")
                    || op.equals("GTE")
                    || op.equals("LT")
                    || op.equals("LTE");
            case BOOLEAN -> op.equals("EQ");
        };
        if (!supported) {
            throw new BizException("FILTER_OP_NOT_SUPPORTED: op "
                    + op
                    + " is not supported for field "
                    + field.sourceColumnName()
                    + " type "
                    + type.name());
        }
    }

    private NormalizedFilterValue normalizeFilterValue(
            PayloadFilterType type,
            String op,
            SelectCondition condition,
            VectorPayloadFieldMeta field
    ) {
        rejectAmbiguousValueCarrier(condition, field);
        if ("IN".equals(op)) {
            return new NormalizedFilterValue(null, requireValues(type, condition, field));
        }
        return new NormalizedFilterValue(requireSingleValue(type, op, condition, field), null);
    }

    private void rejectAmbiguousValueCarrier(SelectCondition condition, VectorPayloadFieldMeta field) {
        if (condition.valueProvided() && condition.valuesProvided()) {
            throw new BizException("FILTER_VALUE_AMBIGUOUS: value and values cannot both be provided for field "
                    + field.sourceColumnName());
        }
    }

    private Object requireSingleValue(
            PayloadFilterType type,
            String op,
            SelectCondition condition,
            VectorPayloadFieldMeta field
    ) {
        if (!condition.valueProvided() || condition.value() == null) {
            throw new BizException("FILTER_VALUE_REQUIRED: value is required for field "
                    + field.sourceColumnName()
                    + " op "
                    + op);
        }
        return normalizeFilterScalar(type, condition.value(), field);
    }

    private List<Object> requireValues(
            PayloadFilterType type,
            SelectCondition condition,
            VectorPayloadFieldMeta field
    ) {
        if (!condition.valuesProvided() || condition.values() == null || condition.values().isEmpty()) {
            throw new BizException("FILTER_VALUE_REQUIRED: values must not be empty for field "
                    + field.sourceColumnName()
                    + " op IN");
        }
        if (condition.values().size() > MAX_FILTER_IN_VALUES) {
            throw new BizException("FILTER_VALUE_TYPE_MISMATCH: values must contain at most "
                    + MAX_FILTER_IN_VALUES
                    + " items for field "
                    + field.sourceColumnName());
        }
        List<Object> values = new ArrayList<>();
        for (Object value : condition.values()) {
            if (value == null) {
                throw new BizException("FILTER_VALUE_TYPE_MISMATCH: IN values for field "
                        + field.sourceColumnName()
                        + " must not contain null");
            }
            values.add(normalizeFilterScalar(type, value, field));
        }
        return values;
    }

    private Object normalizeFilterScalar(PayloadFilterType type, Object value, VectorPayloadFieldMeta field) {
        return switch (type) {
            case STRING -> normalizeString(value, field);
            case INTEGER -> normalizeInteger(value, field);
            case LONG -> normalizeLong(value, field);
            case DOUBLE -> normalizeDouble(value, field);
            case BOOLEAN -> normalizeBoolean(value, field);
        };
    }

    private String normalizeString(Object value, VectorPayloadFieldMeta field) {
        if (value instanceof String text) {
            return text;
        }
        throw filterValueTypeMismatch(field, "STRING");
    }

    private Integer normalizeInteger(Object value, VectorPayloadFieldMeta field) {
        BigInteger number = normalizeIntegralNumber(value, field, "INTEGER");
        if (number.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || number.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw filterValueTypeMismatch(field, "INTEGER");
        }
        return number.intValue();
    }

    private Long normalizeLong(Object value, VectorPayloadFieldMeta field) {
        BigInteger number = normalizeIntegralNumber(value, field, "LONG");
        if (number.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                || number.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw filterValueTypeMismatch(field, "LONG");
        }
        return number.longValue();
    }

    private Double normalizeDouble(Object value, VectorPayloadFieldMeta field) {
        if (!(value instanceof Number number)) {
            throw filterValueTypeMismatch(field, "DOUBLE");
        }
        double normalized = number.doubleValue();
        if (!Double.isFinite(normalized)) {
            throw filterValueTypeMismatch(field, "DOUBLE");
        }
        return normalized;
    }

    private Boolean normalizeBoolean(Object value, VectorPayloadFieldMeta field) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw filterValueTypeMismatch(field, "BOOLEAN");
    }

    private BigInteger normalizeIntegralNumber(Object value, VectorPayloadFieldMeta field, String expectedType) {
        if (!(value instanceof Number number)) {
            throw filterValueTypeMismatch(field, expectedType);
        }
        if (number instanceof Float || number instanceof Double) {
            throw filterValueTypeMismatch(field, expectedType);
        }
        try {
            if (number instanceof BigInteger bigInteger) {
                return bigInteger;
            }
            if (number instanceof BigDecimal bigDecimal) {
                if (bigDecimal.scale() > 0) {
                    throw filterValueTypeMismatch(field, expectedType);
                }
                return bigDecimal.toBigIntegerExact();
            }
            if (number instanceof Byte
                    || number instanceof Short
                    || number instanceof Integer
                    || number instanceof Long) {
                return BigInteger.valueOf(number.longValue());
            }
            BigDecimal decimal = new BigDecimal(number.toString());
            if (decimal.scale() > 0) {
                throw filterValueTypeMismatch(field, expectedType);
            }
            return decimal.toBigIntegerExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw filterValueTypeMismatch(field, expectedType);
        }
    }

    private BizException filterValueTypeMismatch(VectorPayloadFieldMeta field, String expectedType) {
        return new BizException("FILTER_VALUE_TYPE_MISMATCH: field "
                + field.sourceColumnName()
                + " expects "
                + expectedType
                + " value");
    }

    private List<ParsedHit> parseHits(List<VectorEngineDataPort.SearchPointHit> hits, VectorColumnMeta column) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<ParsedHit> parsed = new ArrayList<>();
        for (VectorEngineDataPort.SearchPointHit hit : hits) {
            Map<String, Object> payload = hit.payload() == null ? Map.of() : hit.payload();
            long hitColumnId = requiredLong(payload, SIDECAR_COLUMN_ID_PAYLOAD_KEY);
            if (hitColumnId != column.columnId()) {
                throw new BizException("INCONSISTENT_VECTOR_PAYLOAD: column id mismatch");
            }
            String sourcePk = requiredText(payload, SIDECAR_SOURCE_PK_PAYLOAD_KEY);
            String pkValueType = requiredText(payload, SIDECAR_PK_VALUE_TYPE_PAYLOAD_KEY);
            long vectorIndexVersion = requiredLong(payload, VECTOR_INDEX_VERSION_PAYLOAD_KEY);
            parsed.add(new ParsedHit(
                    pointIdNormalizer.relationalPkValue(sourcePk, pkValueType),
                    sourcePk,
                    hit.score(),
                    vectorIndexVersion
            ));
        }
        return parsed;
    }

    private VectorCollectionMeta readyCollection(long columnId) {
        return vectorCollectionPort.findByColumnId(columnId)
                .stream()
                .filter(collection -> "ACTIVE".equals(collection.servingState())
                        && "READY".equals(collection.collectionStatus()))
                .findFirst()
                .orElseThrow(() -> new BizException("ready vector collection not found for column: " + columnId));
    }

    private String readTargetName(VectorCollectionMeta collection) {
        if (collection.aliasName() != null && !collection.aliasName().isBlank()) {
            return collection.aliasName();
        }
        return collection.collectionName();
    }

    private Optional<VectorPayloadFieldMeta> payloadField(String field, List<VectorPayloadFieldMeta> payloadFields) {
        if (field == null || field.isBlank()) {
            return Optional.empty();
        }
        String normalized = field.trim();
        String normalizedIdentifier = normalized.toUpperCase(Locale.ROOT);
        return payloadFields.stream()
                .filter(payloadField -> payloadField.payloadKey().equalsIgnoreCase(normalized)
                        || normalizeIdentifier(payloadField.sourceColumnName(), "payload sourceColumnName", null)
                        .equals(normalizedIdentifier))
                .findFirst();
    }

    private String sourceColumnOrIdentifier(String field, List<VectorPayloadFieldMeta> payloadFields, String fieldName) {
        return payloadField(field, payloadFields)
                .map(VectorPayloadFieldMeta::sourceColumnName)
                .map(value -> normalizeIdentifier(value, "payload sourceColumnName", null))
                .orElseGet(() -> normalizeIdentifier(field, fieldName, null));
    }

    private void validateReturnColumn(String columnName, VectorColumnMeta column) {
        String normalized = normalizeIdentifier(columnName, "columnName", null);
        if (normalized.equals(column.vectorColumn())) {
            throw new BizException("select field must not be vector column: " + normalized);
        }
        if (normalized.equals(ROW_VERSION_COLUMN)) {
            throw new BizException("select field must not be row version column: " + normalized);
        }
        if (normalized.equals(VECTOR_INDEX_VERSION_COLUMN)) {
            throw new BizException("select field must not be vector index version column: " + normalized);
        }
    }

    private int limit(Integer value, int defaultValue, int maxValue, String fieldName) {
        int normalized = value == null ? defaultValue : value;
        if (normalized <= 0) {
            throw new BizException(fieldName + " must be greater than 0");
        }
        if (normalized > maxValue) {
            throw new BizException(fieldName + " must be less than or equal to " + maxValue);
        }
        return normalized;
    }

    private String normalizeOp(String op) {
        if (op == null || op.isBlank()) {
            throw new BizException("where op must not be blank");
        }
        String normalized = op.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "EQ", "IN", "GT", "GTE", "LT", "LTE", "IS_NULL", "IS_NOT_NULL" -> normalized;
            default -> throw new BizException("unsupported where op: " + op);
        };
    }

    private long requiredLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ex) {
                throw new BizException("INCONSISTENT_VECTOR_PAYLOAD: " + key + " is not a number", ex);
            }
        }
        throw new BizException("INCONSISTENT_VECTOR_PAYLOAD: missing " + key);
    }

    private String requiredText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        if (value instanceof Number number) {
            return String.valueOf(number.longValue());
        }
        throw new BizException("INCONSISTENT_VECTOR_PAYLOAD: missing " + key);
    }

    private String optionalIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeIdentifier(value, fieldName, null);
    }

    private String normalizeIdentifier(String value, String fieldName, String defaultValue) {
        if (value == null || value.isBlank()) {
            if (defaultValue != null) {
                return defaultValue;
            }
            throw new BizException(fieldName + " must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new BizException(fieldName + " must match [A-Za-z][A-Za-z0-9_]*");
        }
        return normalized;
    }

    private String normalizeWithDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private enum PayloadFilterType {
        STRING,
        INTEGER,
        LONG,
        DOUBLE,
        BOOLEAN
    }

    private record NormalizedFilterValue(Object value, List<Object> values) {
    }

    private record ParsedHit(Object pkValue, String sourcePk, double score, long vectorIndexVersion) {
    }
}
