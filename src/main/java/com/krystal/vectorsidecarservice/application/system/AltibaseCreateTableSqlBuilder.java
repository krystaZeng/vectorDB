package com.krystal.vectorsidecarservice.application.system;

import com.krystal.vectorsidecarservice.application.port.in.CreateVectorTableUseCase;
import com.krystal.vectorsidecarservice.application.port.out.RelationalSchemaPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class AltibaseCreateTableSqlBuilder {

    public String build(
            String schemaName,
            String tableName,
            CreateVectorTableUseCase.PrimaryKeySpec primaryKey,
            List<CreateVectorTableUseCase.ScalarColumnSpec> scalarColumns,
            CreateVectorTableUseCase.VectorColumnSpec vectorColumn,
            RelationalSchemaPort.DatabaseDialect dialect
    ) {
        List<String> columnDefinitions = new ArrayList<>();
        columnDefinitions.add("    " + primaryKey.name() + " " + normalizeScalarType(primaryKey.type(), null) + " NOT NULL");

        for (CreateVectorTableUseCase.ScalarColumnSpec scalarColumn : scalarColumns) {
            String typeDef = normalizeScalarType(scalarColumn.type(), scalarColumn.length());
            String nullableSql = Boolean.FALSE.equals(scalarColumn.nullable()) ? " NOT NULL" : "";
            columnDefinitions.add("    " + scalarColumn.name() + " " + typeDef + nullableSql);
        }

        String vectorType = vectorStorageType(vectorColumn.dimension(), vectorColumn.elementType(), dialect);
        String vectorNullableSql = Boolean.FALSE.equals(vectorColumn.nullable()) ? " NOT NULL" : "";
        columnDefinitions.add("    " + vectorColumn.name() + " " + vectorType + vectorNullableSql);
        columnDefinitions.add("    PRIMARY KEY (" + primaryKey.name() + ")");

        return "CREATE TABLE " + schemaName + "." + tableName + " (\n"
                + String.join(",\n", columnDefinitions)
                + "\n)";
    }

    private String normalizeScalarType(String type, Integer length) {
        String normalized = type.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "BIGINT" -> "BIGINT";
            case "INTEGER", "INT" -> "INTEGER";
            case "DOUBLE" -> "DOUBLE";
            case "FLOAT" -> "FLOAT";
            case "TIMESTAMP" -> "TIMESTAMP";
            case "DATE" -> "DATE";
            case "VARCHAR" -> "VARCHAR(" + positiveLength(length, "VARCHAR length") + ")";
            case "CHAR" -> "CHAR(" + positiveLength(length, "CHAR length") + ")";
            default -> throw new BizException("unsupported scalar type: " + normalized);
        };
    }

    private String vectorStorageType(
            int dimension,
            String elementType,
            RelationalSchemaPort.DatabaseDialect dialect
    ) {
        int bytesPerElement = switch (elementType.toUpperCase(Locale.ROOT)) {
            case "FLOAT32" -> 4;
            case "FLOAT16" -> 2;
            case "INT8" -> 1;
            default -> throw new BizException("elementType must be one of FLOAT32, FLOAT16, INT8");
        };
        long storageLength = (long) dimension * bytesPerElement;
        if (storageLength > Integer.MAX_VALUE) {
            throw new BizException("vector storage length is too large");
        }
        String columnType = dialect == RelationalSchemaPort.DatabaseDialect.ALTIBASE ? "VARBYTE" : "VARBINARY";
        return columnType + "(" + storageLength + ")";
    }

    private int positiveLength(Integer length, String fieldName) {
        if (length == null || length <= 0) {
            throw new BizException(fieldName + " must be greater than 0");
        }
        return length;
    }
}
