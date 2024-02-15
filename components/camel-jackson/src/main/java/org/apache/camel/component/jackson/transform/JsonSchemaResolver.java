/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.jackson.transform;

import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.core.FormatSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.jackson.SchemaHelper;
import org.apache.camel.component.jackson.SchemaResolver;
import org.apache.camel.component.jackson.SchemaType;
import org.apache.camel.spi.Resource;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.util.ObjectHelper;

/**
 * Schema resolver able to read Json schema from given exchange being processed. First attempt is to retrieve the Json
 * schema reference from exchange properties. Second approach is to convert a given Json schema given as String in the
 * exchange properties to a proper schema object. Last approach it to load the Json schema as a classpath resource. The
 * following resource path is used to resolve the schema {@code classpath:schemas/json/<T>.json} where T represents the
 * content class name of the exchange body. The content class information is either set as an explicit exchange property
 * or it gets resolved from exchange body type information.
 */
public class JsonSchemaResolver implements SchemaResolver, Processor {
    private final ConcurrentMap<String, JsonNode> schemes;

    private JsonNode schema;
    private String contentClass;

    public JsonSchemaResolver() {
        this.schemes = new ConcurrentHashMap<>();
    }

    public String getSchema() {
        if (this.schema != null) {
            try {
                return Json.mapper().writeValueAsString(this.schema);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        return null;
    }

    public void setSchema(String schema) {
        if (ObjectHelper.isNotEmpty(schema)) {
            try {
                this.schema = Json.mapper().readTree(schema);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        } else {
            this.schema = null;
        }
    }

    public String getContentClass() {
        return contentClass;
    }

    public void setContentClass(String contentClass) {
        if (ObjectHelper.isNotEmpty(contentClass)) {
            this.contentClass = contentClass;
        } else {
            this.contentClass = null;
        }
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Object payload = exchange.getMessage().getBody();
        if (payload == null) {
            return;
        }

        JsonNode answer = computeIfAbsent(exchange);

        if (answer != null) {
            exchange.setProperty(SchemaHelper.CONTENT_SCHEMA, answer);
            exchange.setProperty(SchemaHelper.CONTENT_SCHEMA_TYPE, SchemaType.JSON.type());
            exchange.setProperty(SchemaHelper.CONTENT_CLASS, SchemaHelper.resolveContentClass(exchange, this.contentClass));
        }
    }

    private JsonNode computeIfAbsent(Exchange exchange) {
        if (this.schema != null) {
            return this.schema;
        }

        JsonNode answer = exchange.getProperty(SchemaHelper.CONTENT_SCHEMA, JsonNode.class);

        if (answer == null && exchange.getProperties().containsKey(SchemaHelper.SCHEMA)) {
            String schemaJson = exchange.getProperty(SchemaHelper.SCHEMA, String.class);
            try {
                answer = Json.mapper().readTree(schemaJson);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Unable to load Json schema", e);
            }
        }

        if (answer == null) {
            String contentClass = SchemaHelper.resolveContentClass(exchange, this.contentClass);
            if (contentClass != null) {
                answer = this.schemes.computeIfAbsent(contentClass, t -> {
                    Resource res = PluginHelper.getResourceLoader(exchange.getContext())
                            .resolveResource(
                                    "classpath:schemas/" + SchemaType.JSON.type() + "/" + t + "." + SchemaType.JSON.type());

                    try {
                        if (res.exists()) {
                            try (InputStream is = res.getInputStream()) {
                                if (is != null) {
                                    return Json.mapper().readTree(is);
                                }
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Unable to load Json schema for type: " + t + ", resource: " + res.getLocation(), e);
                    }

                    return null;
                });
            }
        }

        if (answer != null) {
            this.schema = answer;
        }

        return answer;
    }

    @Override
    public FormatSchema resolve(Exchange exchange) {
        JsonNode answer = exchange.getProperty(SchemaHelper.CONTENT_SCHEMA, JsonNode.class);
        if (answer == null) {
            answer = computeIfAbsent(exchange);
        }

        return new JsonFormatSchema(answer);
    }
}
