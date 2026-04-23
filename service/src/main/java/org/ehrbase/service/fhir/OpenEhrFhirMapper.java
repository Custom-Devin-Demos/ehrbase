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

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.composition.Entry;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.composition.Section;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.ItemSingle;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ehrbase.service.fhir.FhirMappingConfig.ArchetypeFhirMapping;
import org.ehrbase.service.fhir.FhirMappingConfig.FhirCodeMapping;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mapper that converts openEHR Reference Model (Archie) objects into HL7 FHIR R4 resources.
 *
 * <p>This mapper traverses the structure of an openEHR {@link Composition} and produces
 * FHIR resources based on the archetype node types encountered. The mapping can be customized
 * via a {@link FhirMappingConfig} for template-driven transformations.</p>
 *
 * <p>Default (generic) mapping behavior when no template-specific config is provided:</p>
 * <ul>
 *   <li>OBSERVATION archetypes → FHIR {@link org.hl7.fhir.r4.model.Observation}</li>
 *   <li>EVALUATION archetypes → FHIR {@link Condition}</li>
 *   <li>Other clinical entries → FHIR {@link org.hl7.fhir.r4.model.Observation} (fallback)</li>
 * </ul>
 */
public class OpenEhrFhirMapper {

    private static final Logger LOG = LoggerFactory.getLogger(OpenEhrFhirMapper.class);

    private static final String OPENEHR_SYSTEM = "http://openehr.org";
    private static final String SNOMED_SYSTEM = "http://snomed.info/sct";
    private static final String LOINC_SYSTEM = "http://loinc.org";

    /**
     * Converts an openEHR Composition into a FHIR R4 Bundle.
     *
     * @param ehrId       UUID of the EHR (used as Patient identifier)
     * @param composition The openEHR Composition to transform
     * @param config      Optional mapping configuration; if null, default mapping is used
     * @return A FHIR R4 Bundle of type COLLECTION containing the mapped resources
     */
    public Bundle mapCompositionToBundle(UUID ehrId, Composition composition, FhirMappingConfig config) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);
        bundle.setId(UUID.randomUUID().toString());

        // Create Patient resource from EHR context
        Patient patient = createPatientFromEhr(ehrId, composition);
        addResourceToBundle(bundle, patient);

        // Traverse composition content and map entries
        if (composition.getContent() != null) {
            for (ContentItem contentItem : composition.getContent()) {
                mapContentItem(contentItem, ehrId, patient, config, bundle);
            }
        }

        return bundle;
    }

    private Patient createPatientFromEhr(UUID ehrId, Composition composition) {
        Patient patient = new Patient();
        patient.setId(ehrId.toString());
        patient.addIdentifier(
                new Identifier().setSystem(OPENEHR_SYSTEM + "/ehr").setValue(ehrId.toString()));

        return patient;
    }

    private void mapContentItem(
            ContentItem contentItem, UUID ehrId, Patient patient, FhirMappingConfig config, Bundle bundle) {

        if (contentItem instanceof Section section) {
            // Recurse into sections
            if (section.getItems() != null) {
                for (ContentItem item : section.getItems()) {
                    mapContentItem(item, ehrId, patient, config, bundle);
                }
            }
        } else if (contentItem instanceof Entry entry) {
            mapEntry(entry, patient, config, bundle);
        }
    }

    private void mapEntry(Entry entry, Patient patient, FhirMappingConfig config, Bundle bundle) {
        String archetypeNodeId = entry.getArchetypeNodeId();
        Optional<ArchetypeFhirMapping> mappingOpt = findMapping(archetypeNodeId, config);

        if (entry instanceof Observation observation) {
            mapObservation(observation, patient, mappingOpt.orElse(null), bundle);
        } else if (entry instanceof Evaluation evaluation) {
            mapEvaluation(evaluation, patient, mappingOpt.orElse(null), bundle);
        } else if (entry instanceof com.nedap.archie.rm.composition.Instruction instruction) {
            mapInstruction(instruction, patient, mappingOpt.orElse(null), bundle);
        } else if (entry instanceof com.nedap.archie.rm.composition.Action action) {
            mapAction(action, patient, mappingOpt.orElse(null), bundle);
        } else {
            LOG.debug(
                    "Unmapped entry type: {} with archetype: {}",
                    entry.getClass().getSimpleName(),
                    archetypeNodeId);
        }
    }

    private void mapObservation(Observation observation, Patient patient, ArchetypeFhirMapping mapping, Bundle bundle) {

        String resourceType = mapping != null ? mapping.getFhirResourceType() : "Observation";

        if ("Observation".equals(resourceType)) {
            org.hl7.fhir.r4.model.Observation fhirObs = new org.hl7.fhir.r4.model.Observation();
            fhirObs.setId(UUID.randomUUID().toString());
            fhirObs.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
            fhirObs.setSubject(new Reference("Patient/" + patient.getId()));

            // Set code from archetype name
            fhirObs.setCode(createCodeableConcept(observation, mapping));

            // Set profile if configured
            if (mapping != null && mapping.getFhirProfileUrl() != null) {
                fhirObs.setMeta(new Meta().addProfile(mapping.getFhirProfileUrl()));
            }

            // Map observation data
            if (observation.getData() != null) {
                mapObservationData(observation.getData(), fhirObs, mapping);
            }

            // Map time context
            if (observation.getData() != null && observation.getData().getOrigin() != null) {
                fhirObs.setEffective(mapDvDateTime(observation.getData().getOrigin()));
            }

            addResourceToBundle(bundle, fhirObs);
        } else {
            LOG.warn("Unexpected resource type '{}' for OBSERVATION archetype", resourceType);
        }
    }

    private void mapEvaluation(Evaluation evaluation, Patient patient, ArchetypeFhirMapping mapping, Bundle bundle) {

        String resourceType = mapping != null ? mapping.getFhirResourceType() : "Condition";

        if ("Condition".equals(resourceType)) {
            Condition condition = new Condition();
            condition.setId(UUID.randomUUID().toString());
            condition.setSubject(new Reference("Patient/" + patient.getId()));
            condition.setCode(createCodeableConcept(evaluation, mapping));

            if (mapping != null && mapping.getFhirProfileUrl() != null) {
                condition.setMeta(new Meta().addProfile(mapping.getFhirProfileUrl()));
            }

            // Extract data from evaluation
            if (evaluation.getData() != null) {
                mapEvaluationData(evaluation.getData(), condition, mapping);
            }

            addResourceToBundle(bundle, condition);
        } else if ("Observation".equals(resourceType)) {
            // Some evaluations may map to Observation (e.g., risk assessments)
            org.hl7.fhir.r4.model.Observation fhirObs = new org.hl7.fhir.r4.model.Observation();
            fhirObs.setId(UUID.randomUUID().toString());
            fhirObs.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
            fhirObs.setSubject(new Reference("Patient/" + patient.getId()));
            fhirObs.setCode(createCodeableConcept(evaluation, mapping));
            addResourceToBundle(bundle, fhirObs);
        } else {
            LOG.warn("Unexpected resource type '{}' for EVALUATION archetype", resourceType);
        }
    }

    private void mapInstruction(
            com.nedap.archie.rm.composition.Instruction instruction,
            Patient patient,
            ArchetypeFhirMapping mapping,
            Bundle bundle) {

        // Instructions typically map to MedicationStatement or MedicationRequest
        MedicationStatement medStatement = new MedicationStatement();
        medStatement.setId(UUID.randomUUID().toString());
        medStatement.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
        medStatement.setSubject(new Reference("Patient/" + patient.getId()));
        medStatement.setMedication(createCodeableConcept(instruction, mapping));

        if (mapping != null && mapping.getFhirProfileUrl() != null) {
            medStatement.setMeta(new Meta().addProfile(mapping.getFhirProfileUrl()));
        }

        addResourceToBundle(bundle, medStatement);
    }

    private void mapAction(
            com.nedap.archie.rm.composition.Action action,
            Patient patient,
            ArchetypeFhirMapping mapping,
            Bundle bundle) {

        // Actions typically represent procedures or medication administrations
        org.hl7.fhir.r4.model.Observation fhirObs = new org.hl7.fhir.r4.model.Observation();
        fhirObs.setId(UUID.randomUUID().toString());
        fhirObs.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
        fhirObs.setSubject(new Reference("Patient/" + patient.getId()));
        fhirObs.setCode(createCodeableConcept(action, mapping));

        if (action.getTime() != null) {
            fhirObs.setEffective(mapDvDateTime(action.getTime()));
        }

        addResourceToBundle(bundle, fhirObs);
    }

    // ---- Data extraction helpers ----

    private void mapObservationData(
            com.nedap.archie.rm.composition.EventContext context,
            org.hl7.fhir.r4.model.Observation fhirObs,
            ArchetypeFhirMapping mapping) {
        // EventContext data extraction is handled separately
    }

    private void mapObservationData(
            com.nedap.archie.rm.datastructures.History<?> history,
            org.hl7.fhir.r4.model.Observation fhirObs,
            ArchetypeFhirMapping mapping) {

        if (history.getEvents() == null || history.getEvents().isEmpty()) {
            return;
        }

        // Map the first event's data items as observation components
        // TODO: Future enhancement — map each event to a separate FHIR Observation for time-series data
        if (history.getEvents().size() > 1) {
            LOG.warn(
                    "History contains {} events but only the first is mapped to FHIR; {} events dropped",
                    history.getEvents().size(),
                    history.getEvents().size() - 1);
        }
        var event = history.getEvents().get(0);
        if (event.getData() instanceof ItemTree itemTree) {
            mapItemTreeToObservation(itemTree, fhirObs, mapping);
        } else if (event.getData() instanceof ItemSingle itemSingle) {
            mapElementToObservationValue(itemSingle.getItem(), fhirObs);
        }
    }

    private void mapItemTreeToObservation(
            ItemTree itemTree, org.hl7.fhir.r4.model.Observation fhirObs, ArchetypeFhirMapping mapping) {

        if (itemTree.getItems() == null) {
            return;
        }

        List<org.hl7.fhir.r4.model.Observation.ObservationComponentComponent> components = new ArrayList<>();

        for (var item : itemTree.getItems()) {
            if (item instanceof Element element) {
                if (components.isEmpty() && fhirObs.getValue() == null) {
                    // First element becomes the primary value
                    mapElementToObservationValue(element, fhirObs);
                } else {
                    // Subsequent elements become components
                    var component = mapElementToComponent(element, mapping);
                    if (component != null) {
                        components.add(component);
                    }
                }
            }
        }

        if (!components.isEmpty()) {
            fhirObs.setComponent(components);
        }
    }

    private void mapElementToObservationValue(Element element, org.hl7.fhir.r4.model.Observation fhirObs) {
        if (element.getValue() == null) {
            return;
        }

        if (element.getValue() instanceof DvQuantity dvQuantity) {
            Quantity quantity = new Quantity();
            quantity.setValue(dvQuantity.getMagnitude());
            quantity.setUnit(dvQuantity.getUnits());
            if (dvQuantity.getUnits() != null) {
                quantity.setSystem("http://unitsofmeasure.org");
                quantity.setCode(dvQuantity.getUnits());
            }
            fhirObs.setValue(quantity);
        } else if (element.getValue() instanceof DvCodedText dvCodedText) {
            fhirObs.setValue(mapDvCodedText(dvCodedText));
        } else if (element.getValue() instanceof DvText dvText) {
            fhirObs.setValue(new StringType(dvText.getValue()));
        } else if (element.getValue() instanceof DvDateTime dvDateTime) {
            fhirObs.setValue(mapDvDateTime(dvDateTime));
        }
    }

    private org.hl7.fhir.r4.model.Observation.ObservationComponentComponent mapElementToComponent(
            Element element, ArchetypeFhirMapping mapping) {

        if (element.getValue() == null) {
            return null;
        }

        var component = new org.hl7.fhir.r4.model.Observation.ObservationComponentComponent();

        // Set component code from element name
        String elementName = element.getName() != null ? element.getName().getValue() : element.getArchetypeNodeId();
        component.setCode(new CodeableConcept().setText(elementName));

        // Check for configured code mappings
        if (mapping != null && mapping.getCodeMappings() != null) {
            FhirCodeMapping codeMapping = mapping.getCodeMappings().get(element.getArchetypeNodeId());
            if (codeMapping != null) {
                component.setCode(new CodeableConcept()
                        .addCoding(new Coding()
                                .setSystem(codeMapping.getFhirSystem())
                                .setCode(codeMapping.getFhirCode())
                                .setDisplay(codeMapping.getFhirDisplay())));
            }
        }

        // Map value
        if (element.getValue() instanceof DvQuantity dvQuantity) {
            Quantity quantity = new Quantity();
            quantity.setValue(dvQuantity.getMagnitude());
            quantity.setUnit(dvQuantity.getUnits());
            if (dvQuantity.getUnits() != null) {
                quantity.setSystem("http://unitsofmeasure.org");
                quantity.setCode(dvQuantity.getUnits());
            }
            component.setValue(quantity);
        } else if (element.getValue() instanceof DvCodedText dvCodedText) {
            component.setValue(mapDvCodedText(dvCodedText));
        } else if (element.getValue() instanceof DvText dvText) {
            component.setValue(new StringType(dvText.getValue()));
        }

        return component;
    }

    private void mapEvaluationData(ItemStructure data, Condition condition, ArchetypeFhirMapping mapping) {
        if (data instanceof ItemTree itemTree && itemTree.getItems() != null) {
            for (var item : itemTree.getItems()) {
                if (item instanceof Element element && element.getValue() != null) {
                    // Map evaluation elements to condition notes or evidence
                    if (element.getValue() instanceof DvCodedText dvCodedText) {
                        condition.addCategory(mapDvCodedText(dvCodedText));
                    } else if (element.getValue() instanceof DvText dvText) {
                        Narrative narrative = new Narrative();
                        narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
                        String escapedText = escapeHtml(dvText.getValue());
                        narrative.setDivAsString(
                                "<div xmlns=\"http://www.w3.org/1999/xhtml\">" + escapedText + "</div>");
                        condition.setText(narrative);
                    } else if (element.getValue() instanceof DvDateTime dvDateTime) {
                        condition.setOnset(mapDvDateTime(dvDateTime));
                    }
                }
            }
        }
    }

    // ---- Type conversion helpers ----

    private CodeableConcept createCodeableConcept(Entry entry, ArchetypeFhirMapping mapping) {
        CodeableConcept concept = new CodeableConcept();

        // Use mapping code if available
        if (mapping != null && mapping.getCodeMappings() != null) {
            FhirCodeMapping rootMapping = mapping.getCodeMappings().get(entry.getArchetypeNodeId());
            if (rootMapping != null) {
                concept.addCoding(new Coding()
                        .setSystem(rootMapping.getFhirSystem())
                        .setCode(rootMapping.getFhirCode())
                        .setDisplay(rootMapping.getFhirDisplay()));
                return concept;
            }
        }

        // Fallback: use archetype node ID and name
        String archetypeId = entry.getArchetypeNodeId();
        concept.addCoding(new Coding().setSystem(OPENEHR_SYSTEM + "/archetype").setCode(archetypeId));

        if (entry.getName() != null) {
            concept.setText(entry.getName().getValue());
        }

        return concept;
    }

    private CodeableConcept mapDvCodedText(DvCodedText dvCodedText) {
        CodeableConcept concept = new CodeableConcept();

        if (dvCodedText.getDefiningCode() != null) {
            Coding coding = new Coding();

            String terminologyId = dvCodedText.getDefiningCode().getTerminologyId() != null
                    ? dvCodedText.getDefiningCode().getTerminologyId().getValue()
                    : null;

            coding.setSystem(mapTerminologyIdToFhirSystem(terminologyId));
            coding.setCode(dvCodedText.getDefiningCode().getCodeString());
            coding.setDisplay(dvCodedText.getValue());
            concept.addCoding(coding);
        }

        concept.setText(dvCodedText.getValue());
        return concept;
    }

    private DateTimeType mapDvDateTime(DvDateTime dvDateTime) {
        if (dvDateTime.getValue() != null) {
            // FHIR requires full ISO 8601 with at least seconds precision
            String isoValue = dvDateTime.getValue().toString();
            // Ensure the datetime has seconds (FHIR rejects truncated formats like "2024-01-15T10:30")
            // Handle both bare (no timezone) and timezone-suffixed formats
            if (isoValue.matches(".*T\\d{2}:\\d{2}$")) {
                isoValue += ":00";
            } else if (isoValue.matches(".*T\\d{2}:\\d{2}[Z+-].*")) {
                isoValue = isoValue.replaceFirst("(T\\d{2}:\\d{2})([Z+-])", "$1:00$2");
            }
            return new DateTimeType(isoValue);
        }
        return new DateTimeType();
    }

    private String mapTerminologyIdToFhirSystem(String terminologyId) {
        if (terminologyId == null) {
            return OPENEHR_SYSTEM + "/terminology";
        }

        return switch (terminologyId.toLowerCase()) {
            case "snomed-ct", "snomed" -> SNOMED_SYSTEM;
            case "loinc" -> LOINC_SYSTEM;
            case "icd10", "icd-10" -> "http://hl7.org/fhir/sid/icd-10";
            case "icd10-cm", "icd-10-cm" -> "http://hl7.org/fhir/sid/icd-10-cm";
            default -> OPENEHR_SYSTEM + "/terminology/" + terminologyId;
        };
    }

    private Optional<ArchetypeFhirMapping> findMapping(String archetypeNodeId, FhirMappingConfig config) {
        if (config == null || config.getMappings() == null || archetypeNodeId == null) {
            return Optional.empty();
        }
        return config.getMappings().stream()
                .filter(m -> m.getArchetypeNodeId() != null
                        && (archetypeNodeId.equals(m.getArchetypeNodeId())
                                || archetypeNodeId.contains(m.getArchetypeNodeId())))
                .findFirst();
    }

    private void addResourceToBundle(Bundle bundle, Resource resource) {
        Bundle.BundleEntryComponent entry = bundle.addEntry();
        entry.setResource(resource);
        entry.setFullUrl("urn:uuid:" + resource.getId());
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
