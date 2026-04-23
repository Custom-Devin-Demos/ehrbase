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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Template-driven mapping configuration that defines how openEHR archetype nodes
 * map to FHIR R4 resource types and elements.
 *
 * <p>Each configuration is associated with an operational template ID and contains
 * a list of {@link ArchetypeFhirMapping} entries that specify which archetype node
 * paths map to which FHIR resource types.</p>
 */
public class FhirMappingConfig {

    private String templateId;
    private List<ArchetypeFhirMapping> mappings;

    public FhirMappingConfig() {
        this.mappings = Collections.emptyList();
    }

    public FhirMappingConfig(String templateId, List<ArchetypeFhirMapping> mappings) {
        this.templateId = Objects.requireNonNull(templateId);
        this.mappings = mappings != null ? mappings : Collections.emptyList();
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public List<ArchetypeFhirMapping> getMappings() {
        return mappings;
    }

    public void setMappings(List<ArchetypeFhirMapping> mappings) {
        this.mappings = mappings;
    }

    /**
     * Defines a single archetype-node-to-FHIR-resource mapping entry.
     *
     * <p>Each entry maps an archetype node ID (e.g., {@code openEHR-EHR-OBSERVATION.blood_pressure.v2})
     * to a target FHIR resource type (e.g., {@code Observation}) with optional code system mappings
     * for coded elements.</p>
     */
    public static class ArchetypeFhirMapping {

        /** The archetype node ID or RM path pattern to match */
        private String archetypeNodeId;

        /** The target FHIR resource type (e.g., "Observation", "Condition", "MedicationStatement") */
        private String fhirResourceType;

        /** FHIR profile URL to set on the resource, if any */
        private String fhirProfileUrl;

        /** Maps openEHR coded term paths to FHIR code system URIs and codes */
        private Map<String, FhirCodeMapping> codeMappings;

        public ArchetypeFhirMapping() {
            this.codeMappings = Collections.emptyMap();
        }

        public ArchetypeFhirMapping(
                String archetypeNodeId,
                String fhirResourceType,
                String fhirProfileUrl,
                Map<String, FhirCodeMapping> codeMappings) {
            this.archetypeNodeId = archetypeNodeId;
            this.fhirResourceType = fhirResourceType;
            this.fhirProfileUrl = fhirProfileUrl;
            this.codeMappings = codeMappings != null ? codeMappings : Collections.emptyMap();
        }

        public String getArchetypeNodeId() {
            return archetypeNodeId;
        }

        public void setArchetypeNodeId(String archetypeNodeId) {
            this.archetypeNodeId = archetypeNodeId;
        }

        public String getFhirResourceType() {
            return fhirResourceType;
        }

        public void setFhirResourceType(String fhirResourceType) {
            this.fhirResourceType = fhirResourceType;
        }

        public String getFhirProfileUrl() {
            return fhirProfileUrl;
        }

        public void setFhirProfileUrl(String fhirProfileUrl) {
            this.fhirProfileUrl = fhirProfileUrl;
        }

        public Map<String, FhirCodeMapping> getCodeMappings() {
            return codeMappings;
        }

        public void setCodeMappings(Map<String, FhirCodeMapping> codeMappings) {
            this.codeMappings = codeMappings;
        }
    }

    /**
     * Maps an openEHR coded term to a FHIR code system and code.
     */
    public static class FhirCodeMapping {

        private String fhirSystem;
        private String fhirCode;
        private String fhirDisplay;

        public FhirCodeMapping() {}

        public FhirCodeMapping(String fhirSystem, String fhirCode, String fhirDisplay) {
            this.fhirSystem = fhirSystem;
            this.fhirCode = fhirCode;
            this.fhirDisplay = fhirDisplay;
        }

        public String getFhirSystem() {
            return fhirSystem;
        }

        public void setFhirSystem(String fhirSystem) {
            this.fhirSystem = fhirSystem;
        }

        public String getFhirCode() {
            return fhirCode;
        }

        public void setFhirCode(String fhirCode) {
            this.fhirCode = fhirCode;
        }

        public String getFhirDisplay() {
            return fhirDisplay;
        }

        public void setFhirDisplay(String fhirDisplay) {
            this.fhirDisplay = fhirDisplay;
        }
    }
}
