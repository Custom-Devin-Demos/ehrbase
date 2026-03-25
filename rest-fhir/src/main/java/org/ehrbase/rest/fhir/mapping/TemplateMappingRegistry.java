/*
 * Copyright (c) 2024 vitasystems GmbH.
 *
 * This file is part of project EHRbase
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ehrbase.rest.fhir.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Registry that loads and caches template mapping definitions from YAML files
 * on the classpath under {@code fhir/mappings/}.
 */
@Component
public class TemplateMappingRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateMappingRegistry.class);
    private static final String MAPPING_LOCATION_PATTERN = "classpath:fhir/mappings/*.yml";

    private final Map<String, TemplateMappingDefinition> mappingsByTemplateId = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    public void loadMappings() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(MAPPING_LOCATION_PATTERN);
            for (Resource resource : resources) {
                loadMapping(resource);
            }
            LOG.info("Loaded {} FHIR template mapping definition(s)", mappingsByTemplateId.size());
        } catch (IOException e) {
            LOG.warn("Failed to scan for FHIR mapping definitions: {}", e.getMessage());
        }
    }

    private void loadMapping(Resource resource) {
        try (InputStream is = resource.getInputStream()) {
            TemplateMappingDefinition definition = yamlMapper.readValue(is, TemplateMappingDefinition.class);
            if (definition.getTemplateId() != null) {
                mappingsByTemplateId.put(definition.getTemplateId(), definition);
                LOG.info(
                        "Loaded FHIR mapping for template '{}' -> {}",
                        definition.getTemplateId(),
                        definition.getFhirResourceType());
            } else {
                LOG.warn("Skipping mapping file {} - missing templateId", resource.getFilename());
            }
        } catch (IOException e) {
            LOG.warn("Failed to load mapping from {}: {}", resource.getFilename(), e.getMessage());
        }
    }

    /**
     * Retrieves the mapping definition for the given template ID.
     */
    public Optional<TemplateMappingDefinition> getMapping(String templateId) {
        return Optional.ofNullable(mappingsByTemplateId.get(templateId));
    }

    /**
     * Returns all registered template IDs.
     */
    public java.util.Set<String> getRegisteredTemplateIds() {
        return mappingsByTemplateId.keySet();
    }
}
