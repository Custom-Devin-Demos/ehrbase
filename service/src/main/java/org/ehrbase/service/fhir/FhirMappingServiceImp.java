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
package org.ehrbase.service.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.nedap.archie.rm.composition.Composition;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.ehrbase.api.exception.InternalServerException;
import org.ehrbase.api.service.FhirMappingService;
import org.ehrbase.api.util.LocatableUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link FhirMappingService} that transforms openEHR Composition
 * RM objects into FHIR R4 Bundle JSON.
 *
 * <p>This service uses a template-driven approach: each operational template can have
 * a custom {@link FhirMappingConfig} that controls how archetype nodes map to FHIR
 * resource types. When no template-specific configuration is registered, a generic
 * default mapping is applied.</p>
 *
 * <p>The FHIR context (HAPI FHIR) is initialized once and reused for thread-safe
 * JSON serialization.</p>
 */
@Service
public class FhirMappingServiceImp implements FhirMappingService {

    private static final Logger LOG = LoggerFactory.getLogger(FhirMappingServiceImp.class);

    private final FhirContext fhirContext;
    private final OpenEhrFhirMapper mapper;
    private final Map<String, FhirMappingConfig> templateMappings;

    public FhirMappingServiceImp() {
        this.fhirContext = FhirContext.forR4();
        this.mapper = new OpenEhrFhirMapper();
        this.templateMappings = new ConcurrentHashMap<>();
    }

    @Override
    public String toFhirBundle(UUID ehrId, Composition composition) {
        Objects.requireNonNull(ehrId, "ehrId must not be null");
        Objects.requireNonNull(composition, "composition must not be null");

        try {
            String templateId = LocatableUtils.getTemplateId(composition);
            FhirMappingConfig config = templateId != null ? templateMappings.get(templateId) : null;

            LOG.debug(
                    "Transforming composition to FHIR R4 Bundle (templateId={}, hasConfig={})",
                    templateId,
                    config != null);

            Bundle bundle = mapper.mapCompositionToBundle(ehrId, composition, config);

            IParser jsonParser = fhirContext.newJsonParser();
            jsonParser.setPrettyPrint(true);

            String fhirJson = jsonParser.encodeResourceToString(bundle);

            LOG.debug(
                    "Successfully produced FHIR Bundle with {} entries",
                    bundle.getEntry().size());

            return fhirJson;
        } catch (Exception e) {
            LOG.error("Failed to transform openEHR Composition to FHIR R4 Bundle", e);
            throw new InternalServerException("Failed to transform composition to FHIR R4: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean hasMappingForTemplate(String templateId) {
        return templateId != null && templateMappings.containsKey(templateId);
    }

    /**
     * Registers a template-specific FHIR mapping configuration.
     *
     * <p>This can be called at application startup to load per-template mappings,
     * or dynamically when new templates are uploaded.</p>
     *
     * @param config The mapping configuration to register
     */
    public void registerMappingConfig(FhirMappingConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(config.getTemplateId(), "config templateId must not be null");
        templateMappings.put(config.getTemplateId(), config);
        LOG.info("Registered FHIR mapping config for template: {}", config.getTemplateId());
    }

    /**
     * Removes a template-specific FHIR mapping configuration.
     *
     * @param templateId The template ID to remove configuration for
     */
    public void removeMappingConfig(String templateId) {
        templateMappings.remove(templateId);
    }
}
