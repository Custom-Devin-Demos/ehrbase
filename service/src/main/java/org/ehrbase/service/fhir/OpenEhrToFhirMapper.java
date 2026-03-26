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
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.composition.Section;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.ItemList;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datastructures.PointEvent;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDate;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import com.nedap.archie.rm.generic.PartyIdentified;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps openEHR {@link Composition} Reference Model objects (from the Archie library) into FHIR R4
 * {@link Bundle} resources.
 *
 * <p>The mapper uses {@link FhirMappingConfig} to resolve archetype node IDs and RM types to FHIR
 * resource types, and walks the composition tree to produce individual FHIR resources collected
 * into a transaction Bundle.
 */
public class OpenEhrToFhirMapper {

    private static final Logger LOG = LoggerFactory.getLogger(OpenEhrToFhirMapper.class);

    private final FhirMappingConfig mappingConfig;

    public OpenEhrToFhirMapper(FhirMappingConfig mappingConfig) {
        this.mappingConfig = mappingConfig;
    }

    /**
     * Maps an openEHR {@link Composition} to a FHIR R4 {@link Bundle}.
     *
     * @param composition the openEHR composition
     * @param templateId  the operational template ID (may be {@code null})
     * @return a FHIR R4 Bundle containing the mapped resources
     */
    public Bundle map(Composition composition, String templateId) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);
        bundle.setId(UUID.randomUUID().toString());

        if (composition.getUid() != null) {
            bundle.setId(composition.getUid().getValue());
        }

        // Map composition-level metadata to an Encounter if context is present
        if (composition.getContext() != null) {
            Encounter encounter = mapCompositionContext(composition);
            addToBundle(bundle, encounter);
        }

        // Walk the content tree and map each entry
        if (composition.getContent() != null) {
            for (ContentItem contentItem : composition.getContent()) {
                List<Resource> resources = mapContentItem(contentItem, templateId);
                resources.forEach(r -> addToBundle(bundle, r));
            }
        }

        return bundle;
    }

    private Encounter mapCompositionContext(Composition composition) {
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID().toString());
        encounter.setStatus(Encounter.EncounterStatus.FINISHED);

        // Map composer to participant
        if (composition.getComposer() instanceof PartyIdentified partyIdentified) {
            encounter.addParticipant()
                    .setIndividual(new Reference().setDisplay(partyIdentified.getName()));
        }

        // Map context start time
        if (composition.getContext().getStartTime() != null) {
            encounter.getPeriod()
                    .setStartElement(
                            mapDvDateTimeToFhir(composition.getContext().getStartTime()));
        }

        // Map context setting
        if (composition.getContext().getSetting() != null) {
            CodeableConcept type = mapDvCodedTextToCodeableConcept(composition.getContext().getSetting());
            encounter.addType(type);
        }

        return encounter;
    }

    /**
     * Recursively processes a content item, dispatching to the appropriate mapper based on RM type.
     */
    private List<Resource> mapContentItem(ContentItem contentItem, String templateId) {
        List<Resource> resources = new ArrayList<>();

        if (contentItem instanceof Section section) {
            // Recursively process section items
            if (section.getItems() != null) {
                for (ContentItem item : section.getItems()) {
                    resources.addAll(mapContentItem(item, templateId));
                }
            }
        } else if (contentItem instanceof Entry entry) {
            resources.addAll(mapEntry(entry, templateId));
        } else {
            LOG.debug("Skipping unsupported content item type: {}", contentItem.getClass().getSimpleName());
        }

        return resources;
    }

    /**
     * Maps an openEHR Entry to one or more FHIR resources based on the archetype node ID and RM
     * type.
     */
    private List<Resource> mapEntry(Entry entry, String templateId) {
        List<Resource> resources = new ArrayList<>();
        String archetypeNodeId = entry.getArchetypeNodeId();
        String rmType = entry.getClass().getSimpleName().toUpperCase();

        String fhirResourceType = mappingConfig.resolveFhirResourceType(templateId, archetypeNodeId, rmType);

        LOG.debug(
                "Mapping entry [archetypeNodeId={}, rmType={}] -> FHIR {}",
                archetypeNodeId,
                rmType,
                fhirResourceType);

        Resource resource = createFhirResource(fhirResourceType, entry);
        if (resource != null) {
            resources.add(resource);
        }

        return resources;
    }

    /**
     * Creates a FHIR resource of the specified type from the given openEHR entry.
     */
    private Resource createFhirResource(String fhirResourceType, Entry entry) {
        return switch (fhirResourceType) {
            case "Observation" -> mapToObservation(entry);
            case "Condition" -> mapToCondition(entry);
            case "MedicationStatement" -> mapToMedicationStatement(entry);
            case "DiagnosticReport" -> mapToDiagnosticReport(entry);
            case "Encounter" -> mapToEncounter(entry);
            default -> mapToObservation(entry); // Safe default
        };
    }

    private org.hl7.fhir.r4.model.Observation mapToObservation(Entry entry) {
        org.hl7.fhir.r4.model.Observation obs = new org.hl7.fhir.r4.model.Observation();
        obs.setId(UUID.randomUUID().toString());
        obs.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);

        // Map the archetype name to code
        if (entry.getName() != null) {
            obs.setCode(mapDvTextToCodeableConcept(entry.getName()));
        }

        // If this is an openEHR Observation, extract data from the History/Events
        if (entry instanceof Observation openEhrObs) {
            mapObservationData(openEhrObs, obs);
        } else {
            // For other entry types mapped to FHIR Observation, extract from protocol/data
            mapGenericEntryData(entry, obs);
        }

        return obs;
    }

    @SuppressWarnings("unchecked")
    private void mapObservationData(Observation openEhrObs, org.hl7.fhir.r4.model.Observation fhirObs) {
        if (openEhrObs.getData() == null) {
            return;
        }

        Object dataObj = openEhrObs.getData();
        if (dataObj instanceof History<?> history) {
            // Map the origin time
            if (history.getOrigin() != null) {
                fhirObs.setEffective(mapDvDateTimeToFhir(history.getOrigin()));
            }

            // Process events
            if (history.getEvents() != null) {
                history.getEvents().stream()
                        .filter(event -> event instanceof PointEvent)
                        .map(event -> (PointEvent<?>) event)
                        .findFirst()
                        .ifPresent(event -> mapPointEventData(event, fhirObs));
            }
        }
    }

    private void mapPointEventData(PointEvent<?> event, org.hl7.fhir.r4.model.Observation fhirObs) {
        if (event.getData() == null) {
            return;
        }

        ItemStructure data = event.getData();
        List<Element> elements = extractElements(data);

        if (elements.size() == 1) {
            // Single value -> set as the observation's main value
            mapElementToObservationValue(elements.get(0), fhirObs);
        } else {
            // Multiple values -> add as components
            for (Element element : elements) {
                mapElementToObservationComponent(element, fhirObs);
            }
        }
    }

    private void mapGenericEntryData(Entry entry, org.hl7.fhir.r4.model.Observation fhirObs) {
        // Try to extract elements from the entry's data attribute if present via reflection-free approach
        // For evaluations and other entries, data is usually in protocol or other_context
        if (entry instanceof com.nedap.archie.rm.composition.Evaluation evaluation) {
            if (evaluation.getData() != null) {
                List<Element> elements = extractElements(evaluation.getData());
                for (Element element : elements) {
                    mapElementToObservationComponent(element, fhirObs);
                }
            }
        }
    }

    private Condition mapToCondition(Entry entry) {
        Condition condition = new Condition();
        condition.setId(UUID.randomUUID().toString());

        // Map entry name to condition code
        if (entry.getName() != null) {
            condition.setCode(mapDvTextToCodeableConcept(entry.getName()));
        }

        // Extract condition-specific data from evaluations
        if (entry instanceof com.nedap.archie.rm.composition.Evaluation evaluation && evaluation.getData() != null) {
            List<Element> elements = extractElements(evaluation.getData());
            for (Element element : elements) {
                mapElementToCondition(element, condition);
            }
        }

        condition.setClinicalStatus(new CodeableConcept(
                new Coding("http://terminology.hl7.org/CodeSystem/condition-clinical", "active", "Active")));

        return condition;
    }

    private void mapElementToCondition(Element element, Condition condition) {
        String elementName = element.getName() != null ? element.getName().getValue() : "";

        if (element.getValue() instanceof DvCodedText codedText) {
            // If the element looks like a diagnosis code, map to condition code
            if (elementName.toLowerCase().contains("diagnosis")
                    || elementName.toLowerCase().contains("problem")) {
                condition.setCode(mapDvCodedTextToCodeableConcept(codedText));
            }
        } else if (element.getValue() instanceof DvDateTime dateTime) {
            condition.setOnset(mapDvDateTimeToFhir(dateTime));
        } else if (element.getValue() instanceof DvDate date) {
            condition.setOnset(new DateTimeType(date.getValue().toString()));
        }
    }

    private MedicationStatement mapToMedicationStatement(Entry entry) {
        MedicationStatement medStatement = new MedicationStatement();
        medStatement.setId(UUID.randomUUID().toString());
        medStatement.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);

        if (entry.getName() != null) {
            medStatement.setMedication(mapDvTextToCodeableConcept(entry.getName()));
        }

        return medStatement;
    }

    private DiagnosticReport mapToDiagnosticReport(Entry entry) {
        DiagnosticReport report = new DiagnosticReport();
        report.setId(UUID.randomUUID().toString());
        report.setStatus(DiagnosticReport.DiagnosticReportStatus.FINAL);

        if (entry.getName() != null) {
            report.setCode(mapDvTextToCodeableConcept(entry.getName()));
        }

        return report;
    }

    private Encounter mapToEncounter(Entry entry) {
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID().toString());
        encounter.setStatus(Encounter.EncounterStatus.FINISHED);

        if (entry.getName() != null) {
            encounter.addType(mapDvTextToCodeableConcept(entry.getName()));
        }

        return encounter;
    }

    // --- Element extraction helpers ---

    private List<Element> extractElements(ItemStructure itemStructure) {
        List<Element> elements = new ArrayList<>();
        if (itemStructure instanceof ItemTree itemTree) {
            if (itemTree.getItems() != null) {
                itemTree.getItems().forEach(item -> {
                    if (item instanceof Element element) {
                        elements.add(element);
                    }
                });
            }
        } else if (itemStructure instanceof ItemList itemList) {
            if (itemList.getItems() != null) {
                itemList.getItems().forEach(elements::add);
            }
        }
        return elements;
    }

    // --- Mapping helper methods ---

    private void mapElementToObservationValue(Element element, org.hl7.fhir.r4.model.Observation obs) {
        if (element.getValue() instanceof DvQuantity dvQuantity) {
            Quantity quantity = new Quantity();
            quantity.setValue(dvQuantity.getMagnitude());
            quantity.setUnit(dvQuantity.getUnits());
            obs.setValue(quantity);
        } else if (element.getValue() instanceof DvCodedText codedText) {
            obs.setValue(mapDvCodedTextToCodeableConcept(codedText));
        } else if (element.getValue() instanceof DvText text) {
            obs.setValue(mapDvTextToCodeableConcept(text));
        }
    }

    private void mapElementToObservationComponent(Element element, org.hl7.fhir.r4.model.Observation obs) {
        org.hl7.fhir.r4.model.Observation.ObservationComponentComponent component =
                new org.hl7.fhir.r4.model.Observation.ObservationComponentComponent();

        if (element.getName() != null) {
            component.setCode(mapDvTextToCodeableConcept(element.getName()));
        }

        if (element.getValue() instanceof DvQuantity dvQuantity) {
            Quantity quantity = new Quantity();
            quantity.setValue(dvQuantity.getMagnitude());
            quantity.setUnit(dvQuantity.getUnits());
            component.setValue(quantity);
        } else if (element.getValue() instanceof DvCodedText codedText) {
            component.setValue(mapDvCodedTextToCodeableConcept(codedText));
        } else if (element.getValue() instanceof DvText text) {
            component.setValue(mapDvTextToCodeableConcept(text));
        }

        obs.addComponent(component);
    }

    private CodeableConcept mapDvCodedTextToCodeableConcept(DvCodedText dvCodedText) {
        CodeableConcept concept = new CodeableConcept();
        concept.setText(dvCodedText.getValue());

        CodePhrase codePhrase = dvCodedText.getDefiningCode();
        if (codePhrase != null) {
            Coding coding = new Coding();
            if (codePhrase.getTerminologyId() != null) {
                coding.setSystem(mapTerminologyIdToUri(
                        codePhrase.getTerminologyId().getValue()));
            }
            coding.setCode(codePhrase.getCodeString());
            coding.setDisplay(dvCodedText.getValue());
            concept.addCoding(coding);
        }

        return concept;
    }

    private CodeableConcept mapDvTextToCodeableConcept(DvText dvText) {
        if (dvText instanceof DvCodedText codedText) {
            return mapDvCodedTextToCodeableConcept(codedText);
        }
        CodeableConcept concept = new CodeableConcept();
        concept.setText(dvText.getValue());
        return concept;
    }

    private DateTimeType mapDvDateTimeToFhir(DvDateTime dvDateTime) {
        if (dvDateTime == null || dvDateTime.getValue() == null) {
            return null;
        }
        return new DateTimeType(dvDateTime.getValue().toString());
    }

    /**
     * Maps an openEHR terminology ID to a FHIR system URI.
     */
    private String mapTerminologyIdToUri(String terminologyId) {
        if (terminologyId == null) {
            return null;
        }
        return switch (terminologyId) {
            case "SNOMED-CT", "SNOMED" -> "http://snomed.info/sct";
            case "LOINC" -> "http://loinc.org";
            case "ICD10", "ICD-10" -> "http://hl7.org/fhir/sid/icd-10";
            case "ICD10-CM" -> "http://hl7.org/fhir/sid/icd-10-cm";
            case "ICD9", "ICD-9" -> "http://hl7.org/fhir/sid/icd-9";
            case "UCUM" -> "http://unitsofmeasure.org";
            case "ATC" -> "http://www.whocc.no/atc";
            case "openehr" -> "http://openehr.org/id";
            case "local" -> "http://openehr.org/local";
            default -> terminologyId;
        };
    }

    private void addToBundle(Bundle bundle, Resource resource) {
        Bundle.BundleEntryComponent entry = bundle.addEntry();
        entry.setResource(resource);
        entry.setFullUrl("urn:uuid:" + resource.getId());

        Bundle.BundleEntryRequestComponent request = new Bundle.BundleEntryRequestComponent();
        request.setMethod(Bundle.HTTPVerb.POST);
        request.setUrl(resource.fhirType());
        entry.setRequest(request);
    }
}
