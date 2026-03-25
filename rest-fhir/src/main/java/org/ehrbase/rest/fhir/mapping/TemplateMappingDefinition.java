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

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Configuration model for template-specific openEHR-to-FHIR field mappings.
 * Loaded from YAML mapping definition files.
 */
public class TemplateMappingDefinition {

    @JsonProperty("templateId")
    private String templateId;

    @JsonProperty("fhirResourceType")
    private String fhirResourceType;

    @JsonProperty("rmType")
    private String rmType;

    @JsonProperty("description")
    private String description;

    @JsonProperty("profile")
    private String profile;

    @JsonProperty("codingSystem")
    private String codingSystem;

    @JsonProperty("codingCode")
    private String codingCode;

    @JsonProperty("codingDisplay")
    private String codingDisplay;

    @JsonProperty("categorySystem")
    private String categorySystem;

    @JsonProperty("categoryCode")
    private String categoryCode;

    @JsonProperty("categoryDisplay")
    private String categoryDisplay;

    @JsonProperty("fieldMappings")
    private List<FieldMapping> fieldMappings;

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getFhirResourceType() {
        return fhirResourceType;
    }

    public void setFhirResourceType(String fhirResourceType) {
        this.fhirResourceType = fhirResourceType;
    }

    public String getRmType() {
        return rmType;
    }

    public void setRmType(String rmType) {
        this.rmType = rmType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getCodingSystem() {
        return codingSystem;
    }

    public void setCodingSystem(String codingSystem) {
        this.codingSystem = codingSystem;
    }

    public String getCodingCode() {
        return codingCode;
    }

    public void setCodingCode(String codingCode) {
        this.codingCode = codingCode;
    }

    public String getCodingDisplay() {
        return codingDisplay;
    }

    public void setCodingDisplay(String codingDisplay) {
        this.codingDisplay = codingDisplay;
    }

    public String getCategorySystem() {
        return categorySystem;
    }

    public void setCategorySystem(String categorySystem) {
        this.categorySystem = categorySystem;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    public String getCategoryDisplay() {
        return categoryDisplay;
    }

    public void setCategoryDisplay(String categoryDisplay) {
        this.categoryDisplay = categoryDisplay;
    }

    public List<FieldMapping> getFieldMappings() {
        return fieldMappings;
    }

    public void setFieldMappings(List<FieldMapping> fieldMappings) {
        this.fieldMappings = fieldMappings;
    }

    /**
     * Defines a single field mapping between an openEHR archetype path and a FHIR resource field.
     */
    public static class FieldMapping {

        @JsonProperty("archetypePath")
        private String archetypePath;

        @JsonProperty("fhirPath")
        private String fhirPath;

        @JsonProperty("type")
        private String type;

        @JsonProperty("unit")
        private String unit;

        @JsonProperty("ucumUnit")
        private String ucumUnit;

        @JsonProperty("system")
        private String system;

        @JsonProperty("required")
        private boolean required;

        @JsonProperty("coding")
        private Map<String, String> coding;

        public String getArchetypePath() {
            return archetypePath;
        }

        public void setArchetypePath(String archetypePath) {
            this.archetypePath = archetypePath;
        }

        public String getFhirPath() {
            return fhirPath;
        }

        public void setFhirPath(String fhirPath) {
            this.fhirPath = fhirPath;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public String getUcumUnit() {
            return ucumUnit;
        }

        public void setUcumUnit(String ucumUnit) {
            this.ucumUnit = ucumUnit;
        }

        public String getSystem() {
            return system;
        }

        public void setSystem(String system) {
            this.system = system;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public Map<String, String> getCoding() {
            return coding;
        }

        public void setCoding(Map<String, String> coding) {
            this.coding = coding;
        }
    }
}
