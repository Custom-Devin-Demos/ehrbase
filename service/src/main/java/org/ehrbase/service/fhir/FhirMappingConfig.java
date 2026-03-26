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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Template-driven mapping configuration that associates openEHR RM types and archetype node IDs
 * with FHIR R4 resource types.
 *
 * <p>Mapping rules are resolved in this order:
 * <ol>
 *   <li>Template-specific overrides (keyed by template ID + archetype node ID)</li>
 *   <li>Global archetype node ID mappings</li>
 *   <li>Default RM type to FHIR resource type mappings</li>
 * </ol>
 */
public class FhirMappingConfig {

    /**
     * Maps openEHR RM type names to their default FHIR resource type.
     */
    private final Map<String, String> rmTypeToFhirResource;

    /**
     * Maps archetype node IDs (e.g. "openEHR-EHR-OBSERVATION.blood_pressure.v2") to FHIR resource
     * types. These are global mappings that apply regardless of template.
     */
    private final Map<String, String> archetypeToFhirResource;

    /**
     * Template-specific overrides. Outer key is template ID, inner key is archetype node ID,
     * value is FHIR resource type.
     */
    private final Map<String, Map<String, String>> templateOverrides;

    public FhirMappingConfig() {
        this.rmTypeToFhirResource = new HashMap<>();
        this.archetypeToFhirResource = new HashMap<>();
        this.templateOverrides = new HashMap<>();
        initializeDefaults();
    }

    private void initializeDefaults() {
        // Default RM type to FHIR resource mappings
        rmTypeToFhirResource.put("OBSERVATION", "Observation");
        rmTypeToFhirResource.put("EVALUATION", "Condition");
        rmTypeToFhirResource.put("INSTRUCTION", "MedicationRequest");
        rmTypeToFhirResource.put("ACTION", "Procedure");
        rmTypeToFhirResource.put("ADMIN_ENTRY", "Encounter");
        rmTypeToFhirResource.put("CLUSTER", "Observation");
        rmTypeToFhirResource.put("SECTION", "Observation");

        // Common archetype node ID to FHIR resource mappings
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.blood_pressure.v2", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.blood_pressure.v1", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.body_temperature.v2", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.body_temperature.v1", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.body_weight.v2", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.body_weight.v1", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.body_mass_index.v2", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.height.v2", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.height.v1", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.pulse_oximetry.v1", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.pulse.v2", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.respiration.v2", "Observation");
        archetypeToFhirResource.put("openEHR-EHR-OBSERVATION.laboratory_test_result.v1", "DiagnosticReport");
        archetypeToFhirResource.put("openEHR-EHR-EVALUATION.problem_diagnosis.v1", "Condition");
        archetypeToFhirResource.put("openEHR-EHR-EVALUATION.medication_summary.v1", "MedicationStatement");
        archetypeToFhirResource.put("openEHR-EHR-INSTRUCTION.medication_order.v3", "MedicationRequest");
        archetypeToFhirResource.put("openEHR-EHR-INSTRUCTION.medication_order.v2", "MedicationRequest");
        archetypeToFhirResource.put("openEHR-EHR-ACTION.procedure.v1", "Procedure");
        archetypeToFhirResource.put("openEHR-EHR-EVALUATION.clinical_synopsis.v1", "ClinicalImpression");
        archetypeToFhirResource.put("openEHR-EHR-ADMIN_ENTRY.admission.v0", "Encounter");
        archetypeToFhirResource.put("openEHR-EHR-ADMIN_ENTRY.discharge_summary.v0", "Encounter");
        archetypeToFhirResource.put("openEHR-EHR-EVALUATION.adverse_reaction_risk.v1", "AllergyIntolerance");
        archetypeToFhirResource.put("openEHR-EHR-CLUSTER.laboratory_test_analyte.v1", "Observation");
    }

    /**
     * Resolves the FHIR resource type for a given archetype node ID and RM type, optionally within
     * the context of a specific template.
     *
     * @param templateId    the operational template ID (may be {@code null})
     * @param archetypeNodeId the archetype node ID
     * @param rmType        the openEHR RM type name
     * @return the FHIR resource type to use
     */
    public String resolveFhirResourceType(String templateId, String archetypeNodeId, String rmType) {
        // 1. Check template-specific overrides
        if (templateId != null) {
            Optional<String> templateSpecific = Optional.ofNullable(templateOverrides.get(templateId))
                    .map(m -> m.get(archetypeNodeId));
            if (templateSpecific.isPresent()) {
                return templateSpecific.get();
            }
        }

        // 2. Check global archetype mappings
        String archetypeMapping = archetypeToFhirResource.get(archetypeNodeId);
        if (archetypeMapping != null) {
            return archetypeMapping;
        }

        // 3. Fall back to RM type mapping
        return rmTypeToFhirResource.getOrDefault(rmType, "Basic");
    }

    /**
     * Registers a template-specific mapping override.
     */
    public void addTemplateOverride(String templateId, String archetypeNodeId, String fhirResourceType) {
        templateOverrides.computeIfAbsent(templateId, k -> new HashMap<>()).put(archetypeNodeId, fhirResourceType);
    }

    /**
     * Registers a global archetype-to-FHIR resource type mapping.
     */
    public void addArchetypeMapping(String archetypeNodeId, String fhirResourceType) {
        archetypeToFhirResource.put(archetypeNodeId, fhirResourceType);
    }

    public Map<String, String> getRmTypeToFhirResource() {
        return Collections.unmodifiableMap(rmTypeToFhirResource);
    }

    public Map<String, String> getArchetypeToFhirResource() {
        return Collections.unmodifiableMap(archetypeToFhirResource);
    }
}
