package com.eia.directclaims.expert;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * Reads and writes PostgreSQL {@code text[]} columns through JDBC. Values are
 * bound as a parameter and cast in SQL, so no caller text is concatenated into
 * a statement.
 */
final class SqlTextArray {
    private SqlTextArray() {
    }

    static String literal(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"');
            for (char character : values.get(i).toCharArray()) {
                if (character == '\\' || character == '"') {
                    builder.append('\\');
                }
                builder.append(character);
            }
            builder.append('"');
        }
        return builder.append('}').toString();
    }

    static List<String> read(ResultSet resultSet, String column) throws SQLException {
        Array array = resultSet.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return List.copyOf(Arrays.asList(values));
        }
        if (raw instanceof Object[] values) {
            return Arrays.stream(values).map(String::valueOf).toList();
        }
        return List.of();
    }
}
