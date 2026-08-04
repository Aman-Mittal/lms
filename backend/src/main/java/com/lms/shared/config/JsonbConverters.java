/*
 * Copyright 2026 Aman Mittal
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
package com.lms.shared.config;

import java.sql.SQLException;

import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * Maps {@link Json} values to and from Postgres {@code jsonb} columns.
 *
 * <p>Needed in both directions, for different reasons. Reading, the driver
 * returns a {@link PGobject} rather than a string. Writing, sending a plain
 * string fails with "column is of type jsonb but expression is of type
 * character varying" -- Postgres will not implicitly cast a bind parameter, so
 * the value must be wrapped in a {@code PGobject} that declares its type.
 *
 * <p>The conversion is keyed on {@link Json} rather than {@code String}
 * precisely so it does not capture every text property in the application.
 */
public final class JsonbConverters {

    private JsonbConverters() {
    }

    @ReadingConverter
    public enum PgObjectToJson implements Converter<PGobject, Json> {
        INSTANCE;

        @Override
        public Json convert(PGobject source) {
            return Json.of(source.getValue());
        }
    }

    @WritingConverter
    public enum JsonToPgObject implements Converter<Json, PGobject> {
        INSTANCE;

        @Override
        public PGobject convert(Json source) {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            try {
                pgObject.setValue(source.value());
            } catch (SQLException e) {
                throw new IllegalArgumentException("Value is not valid JSON for a jsonb column", e);
            }
            return pgObject;
        }
    }
}
