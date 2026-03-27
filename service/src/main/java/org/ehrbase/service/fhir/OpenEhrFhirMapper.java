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

import com.nedap.archie.rm.composition.Action;
import com.nedap.archie.rm.composition.AdminEntry;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.composition.Entry;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.Instruction;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.composition.Section;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.ItemSingle;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.DvCount;
import com.nedap.archie.rm.datavalues.quantity.DvOrdinal;
import com.nedap.archie.rm.datavalues.quantity.DvProportion;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDate;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvTime;
import com.nedap.archie.rm.generic.PartyIdentified;
import com.nedap.archie.rm.generic.PartyProxy;
import com.nedap.archie.rm.generic.PartySelf;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.utilities.xhtml.NodeType;
import org.hl7.fhir.utilities.xhtml.XhtmlNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps openEHR Composition RM objects (from the Archie library) into FHIR R4 Bundle resources.
 *
 * <p>The mapper traverses the composition's content hierarchy (Sections &rarr; Entries &rarr; data
 * structures &rarr; Elements) and converts each clinical entry into the corresponding FHIR resource
 * using the {@link ArchetypeToFhirMapping} configuration. Data values (DvQuantity, DvCodedText,
 * DvDateTime, etc.) are converted to their FHIR equivalents (Quantity, CodeableConcept,
 * DateTimeType, etc.).
 *
 * <p>A {@link Patient} resource is derived from the composition's entry subjects (via
 * {@code Entry.getSubject()}) with a fallback to the composition's composer, and included as the
 * first entry in the Bundle to provide patient context for all other resources.
 */
public class OpenEhrFhirMapper {

    private static final Logger LOG = LoggerFactory.getLogger(OpenEhrFhirMapper.class);

    /**
     * Transforms a full openEHR {@link Composition} into a FHIR R4 {@link Bundle}.
     *
     * @param composition the openEHR Composition to transform
     * @return a FHIR R4 Bundle of type COLLECTION with mapped resources
     */
    public Bundle mapCompositionToBundle(Composition composition) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);
        bundle.setId(UUID.randomUUID().toString());

        // Extract patient from composition subject/composer and add as first entry
        Patient patient = extractPatient(composition);
        addBundleEntry(bundle, patient);

        // Traverse the content items of the composition
        if (composition.getContent() != null) {
            for (ContentItem contentItem : composition.getContent()) {
                List<Resource> resources = mapContentItem(contentItem, patient);
                for (Resource resource : resources) {
                    addBundleEntry(bundle, resource);
                }
            }
        }

        return bundle;
    }

    /**
     * Recursively maps a content item. Sections are traversed to find nested entries.
     */
    private List<Resource> mapContentItem(ContentItem contentItem, Patient patient) {
        List<Resource> resources = new ArrayList<>();

        if (contentItem instanceof Section section) {
            // Sections contain nested content items
            if (section.getItems() != null) {
                for (ContentItem nested : section.getItems()) {
                    resources.addAll(mapContentItem(nested, patient));
                }
            }
        } else if (contentItem instanceof Entry entry) {
            Resource resource = mapEntry(entry, patient);
            if (resource != null) {
                resources.add(resource);
            }
        } else {
            LOG.warn("Unsupported content item type: {}", contentItem.getClass().getSimpleName());
        }

        return resources;
    }

    /**
     * Maps a single openEHR Entry to the corresponding FHIR resource based on RM type.
     */
    private Resource mapEntry(Entry entry, Patient patient) {
        if (entry instanceof Observation observation) {
            return mapObservation(observation, patient);
        } else if (entry instanceof Evaluation evaluation) {
            return mapEvaluation(evaluation, patient);
        } else if (entry instanceof Instruction instruction) {
            return mapInstruction(instruction, patient);
        } else if (entry instanceof Action action) {
            return mapAction(action, patient);
        } else if (entry instanceof AdminEntry adminEntry) {
            return mapAdminEntry(adminEntry, patient);
        }

        LOG.warn(
                "No FHIR mapping for entry type: {} (archetype: {})",
                entry.getClass().getSimpleName(),
                entry.getArchetypeNodeId());
        return null;
    }

    /**
     * Maps an openEHR OBSERVATION to a FHIR Observation resource.
     */
    private org.hl7.fhir.r4.model.Observation mapObservation(Observation openEhrObs, Patient patient) {
        org.hl7.fhir.r4.model.Observation fhirObs = new org.hl7.fhir.r4.model.Observation();
        fhirObs.setId(UUID.randomUUID().toString());
        fhirObs.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
        fhirObs.setSubject(new Reference("Patient/" + patient.getId()));

        // Set code from archetype name
        fhirObs.setCode(createCodeableConcept(openEhrObs.getName()));

        // Set narrative text
        setNarrative(fhirObs, openEhrObs.getName());

        // Map data from the observation's data attribute (HISTORY -> EVENTs -> ITEM_TREE)
        if (openEhrObs.getData() != null
                && openEhrObs.getData().getEvents() != null
                && !openEhrObs.getData().getEvents().isEmpty()) {

            var event = openEhrObs.getData().getEvents().get(0);

            // Map time if present
            if (event.getTime() != null && event.getTime().getValue() != null) {
                fhirObs.setEffective(new DateTimeType(event.getTime().getValue().toString()));
            }

            // Map data elements from the event's data
            if (event.getData() instanceof ItemTree itemTree) {
                mapItemTreeToObservation(fhirObs, itemTree);
            } else if (event.getData() instanceof ItemSingle itemSingle) {
                if (itemSingle.getItem() != null) {
                    Type value = mapDataValue(itemSingle.getItem().getValue());
                    if (value != null) {
                        fhirObs.setValue(value);
                    }
                }
            }
        }

        return fhirObs;
    }

    /**
     * Maps elements of an ItemTree into a FHIR Observation (value + components).
     */
    private void mapItemTreeToObservation(org.hl7.fhir.r4.model.Observation fhirObs, ItemTree itemTree) {
        if (itemTree.getItems() == null || itemTree.getItems().isEmpty()) {
            return;
        }

        // If there's a single element, use it as the observation value
        if (itemTree.getItems().size() == 1 && itemTree.getItems().get(0) instanceof Element element) {
            Type value = mapDataValue(element.getValue());
            if (value != null) {
                fhirObs.setValue(value);
            }
            return;
        }

        // Multiple elements -> map as observation components
        for (var item : itemTree.getItems()) {
            if (item instanceof Element element) {
                org.hl7.fhir.r4.model.Observation.ObservationComponentComponent component =
                        new org.hl7.fhir.r4.model.Observation.ObservationComponentComponent();
                component.setCode(createCodeableConcept(element.getName()));
                Type value = mapDataValue(element.getValue());
                if (value != null) {
                    component.setValue(value);
                }
                fhirObs.addComponent(component);
            }
        }
    }

    /**
     * Maps an openEHR EVALUATION to a FHIR Condition resource.
     */
    private Condition mapEvaluation(Evaluation evaluation, Patient patient) {
        Condition condition = new Condition();
        condition.setId(UUID.randomUUID().toString());
        condition.setSubject(new Reference("Patient/" + patient.getId()));
        condition.setClinicalStatus(new CodeableConcept(
                new Coding("http://terminology.hl7.org/CodeSystem/condition-clinical", "active", "Active")));
        condition.setVerificationStatus(new CodeableConcept(
                new Coding("http://terminology.hl7.org/CodeSystem/condition-ver-status", "confirmed", "Confirmed")));

        // Set code from archetype name
        condition.setCode(createCodeableConcept(evaluation.getName()));

        // Set narrative text
        setNarrative(condition, evaluation.getName());

        // Map data elements
        if (evaluation.getData() instanceof ItemTree itemTree && itemTree.getItems() != null) {
            for (var item : itemTree.getItems()) {
                if (item instanceof Element element) {
                    mapEvaluationElement(condition, element);
                }
            }
        }

        return condition;
    }

    /**
     * Maps individual elements from an evaluation into the Condition resource.
     */
    private void mapEvaluationElement(Condition condition, Element element) {
        if (element.getValue() instanceof DvCodedText dvCodedText) {
            // Coded values might represent the condition code itself
            String nodeName = element.getName() != null ? element.getName().getValue() : "";
            if (nodeName.toLowerCase().contains("problem")
                    || nodeName.toLowerCase().contains("diagnosis")
                    || nodeName.toLowerCase().contains("condition")) {
                condition.setCode(createCodeableConceptFromDvCodedText(dvCodedText));
            } else {
                condition.addCategory(createCodeableConceptFromDvCodedText(dvCodedText));
            }
        } else if (element.getValue() instanceof DvDateTime dvDateTime) {
            if (dvDateTime.getValue() != null) {
                condition.setOnset(new DateTimeType(dvDateTime.getValue().toString()));
            }
        } else if (element.getValue() instanceof DvText dvText) {
            condition.addNote().setText(dvText.getValue());
        }
    }

    /**
     * Maps an openEHR INSTRUCTION to a FHIR MedicationRequest resource.
     */
    private MedicationRequest mapInstruction(Instruction instruction, Patient patient) {
        MedicationRequest medRequest = new MedicationRequest();
        medRequest.setId(UUID.randomUUID().toString());
        medRequest.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
        medRequest.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
        medRequest.setSubject(new Reference("Patient/" + patient.getId()));

        // Set medication from archetype name
        medRequest.setMedication(createCodeableConcept(instruction.getName()));

        // Set narrative text
        setNarrative(medRequest, instruction.getName());

        // Map activities if present
        if (instruction.getActivities() != null && !instruction.getActivities().isEmpty()) {
            var activity = instruction.getActivities().get(0);
            if (activity.getDescription() instanceof ItemTree itemTree && itemTree.getItems() != null) {
                for (var item : itemTree.getItems()) {
                    if (item instanceof Element element && element.getValue() instanceof DvCodedText dvCodedText) {
                        medRequest.setMedication(createCodeableConceptFromDvCodedText(dvCodedText));
                        break;
                    }
                }
            }
        }

        return medRequest;
    }

    /**
     * Maps an openEHR ACTION to a FHIR MedicationStatement resource.
     */
    private MedicationStatement mapAction(Action action, Patient patient) {
        MedicationStatement medStatement = new MedicationStatement();
        medStatement.setId(UUID.randomUUID().toString());
        medStatement.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
        medStatement.setSubject(new Reference("Patient/" + patient.getId()));

        // Set medication from archetype name
        medStatement.setMedication(createCodeableConcept(action.getName()));

        // Set narrative text
        setNarrative(medStatement, action.getName());

        // Map time from ACTION.time
        if (action.getTime() != null && action.getTime().getValue() != null) {
            medStatement.setEffective(
                    new DateTimeType(action.getTime().getValue().toString()));
        }

        // Map description data
        if (action.getDescription() instanceof ItemTree itemTree && itemTree.getItems() != null) {
            for (var item : itemTree.getItems()) {
                if (item instanceof Element element && element.getValue() instanceof DvCodedText dvCodedText) {
                    medStatement.setMedication(createCodeableConceptFromDvCodedText(dvCodedText));
                    break;
                }
            }
        }

        return medStatement;
    }

    /**
     * Maps an openEHR ADMIN_ENTRY to a FHIR Encounter resource.
     */
    private Encounter mapAdminEntry(AdminEntry adminEntry, Patient patient) {
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID().toString());
        encounter.setStatus(Encounter.EncounterStatus.FINISHED);
        encounter.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", "ambulatory"));
        encounter.setSubject(new Reference("Patient/" + patient.getId()));

        // Set narrative text
        setNarrative(encounter, adminEntry.getName());

        // Map data from admin entry
        if (adminEntry.getData() instanceof ItemTree itemTree && itemTree.getItems() != null) {
            for (var item : itemTree.getItems()) {
                if (item instanceof Element element && element.getValue() instanceof DvCodedText dvCodedText) {
                    encounter.addType(createCodeableConceptFromDvCodedText(dvCodedText));
                }
            }
        }

        return encounter;
    }

    // ---- Data value mapping methods ----

    /**
     * Maps an openEHR DataValue to the corresponding FHIR Type.
     *
     * @param dataValue the openEHR data value
     * @return the corresponding FHIR Type, or {@code null} if unmappable
     */
    Type mapDataValue(com.nedap.archie.rm.datavalues.DataValue dataValue) {
        if (dataValue == null) {
            return null;
        }

        if (dataValue instanceof DvQuantity dvQuantity) {
            return mapDvQuantity(dvQuantity);
        } else if (dataValue instanceof DvCount dvCount) {
            return mapDvCount(dvCount);
        } else if (dataValue instanceof DvProportion dvProportion) {
            return mapDvProportion(dvProportion);
        } else if (dataValue instanceof DvOrdinal dvOrdinal) {
            return mapDvOrdinal(dvOrdinal);
        } else if (dataValue instanceof DvCodedText dvCodedText) {
            return createCodeableConceptFromDvCodedText(dvCodedText);
        } else if (dataValue instanceof DvText dvText) {
            return new StringType(dvText.getValue());
        } else if (dataValue instanceof DvDateTime dvDateTime) {
            return dvDateTime.getValue() != null
                    ? new DateTimeType(dvDateTime.getValue().toString())
                    : null;
        } else if (dataValue instanceof DvDate dvDate) {
            return dvDate.getValue() != null
                    ? new org.hl7.fhir.r4.model.DateType(dvDate.getValue().toString())
                    : null;
        } else if (dataValue instanceof DvTime dvTime) {
            return dvTime.getValue() != null
                    ? new org.hl7.fhir.r4.model.TimeType(dvTime.getValue().toString())
                    : null;
        } else if (dataValue instanceof com.nedap.archie.rm.datavalues.DvBoolean dvBoolean) {
            return new org.hl7.fhir.r4.model.BooleanType(dvBoolean.getValue());
        } else if (dataValue instanceof com.nedap.archie.rm.datavalues.DvIdentifier dvIdentifier) {
            return new StringType(dvIdentifier.getId());
        }

        LOG.debug("Unmapped data value type: {}", dataValue.getClass().getSimpleName());
        return null;
    }

    private Quantity mapDvQuantity(DvQuantity dvQuantity) {
        Quantity quantity = new Quantity();
        if (dvQuantity.getMagnitude() != null) {
            quantity.setValue(BigDecimal.valueOf(dvQuantity.getMagnitude()));
        }
        quantity.setUnit(dvQuantity.getUnits());
        // UCUM system for units
        quantity.setSystem("http://unitsofmeasure.org");
        quantity.setCode(dvQuantity.getUnits());
        return quantity;
    }

    private Quantity mapDvCount(DvCount dvCount) {
        Quantity quantity = new Quantity();
        if (dvCount.getMagnitude() != null) {
            quantity.setValue(BigDecimal.valueOf(dvCount.getMagnitude()));
        }
        quantity.setUnit("1");
        quantity.setSystem("http://unitsofmeasure.org");
        quantity.setCode("1");
        return quantity;
    }

    private Quantity mapDvProportion(DvProportion dvProportion) {
        Quantity quantity = new Quantity();
        if (dvProportion.getNumerator() != null && dvProportion.getDenominator() != null) {
            double value = dvProportion.getDenominator() != 0
                    ? dvProportion.getNumerator() / dvProportion.getDenominator()
                    : 0;
            quantity.setValue(BigDecimal.valueOf(value));
        }
        quantity.setUnit("%");
        return quantity;
    }

    private Quantity mapDvOrdinal(DvOrdinal dvOrdinal) {
        Quantity quantity = new Quantity();
        if (dvOrdinal.getValue() != null) {
            quantity.setValue(BigDecimal.valueOf(dvOrdinal.getValue()));
        }
        if (dvOrdinal.getSymbol() != null) {
            quantity.setUnit(dvOrdinal.getSymbol().getValue());
        }
        return quantity;
    }

    // ---- Helper methods ----

    /**
     * Extracts a FHIR Patient resource from the composition.
     *
     * <p>In openEHR, the <em>subject</em> of each {@link Entry} represents the patient the record
     * is about, while {@code Composition.getComposer()} identifies the healthcare provider who
     * authored the record. This method first searches the composition's content entries for a
     * subject that is a {@link PartyIdentified} (i.e. the actual patient). If no such subject is
     * found, it falls back to the composer as a best-effort default.
     */
    Patient extractPatient(Composition composition) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID().toString());

        // First try to extract patient from entry subjects (the actual patient)
        PartyProxy subject = findEntrySubject(composition);

        // Fall back to composer only if no entry subject is available
        if (subject == null) {
            subject = composition.getComposer();
        }

        if (subject instanceof PartyIdentified partyIdentified) {
            if (partyIdentified.getName() != null) {
                patient.addName().setText(partyIdentified.getName());
            }
            if (partyIdentified.getExternalRef() != null
                    && partyIdentified.getExternalRef().getId() != null) {
                patient.addIdentifier()
                        .setValue(partyIdentified.getExternalRef().getId().getValue());
            }
        } else if (subject instanceof PartySelf) {
            patient.addName().setText("Self");
        }

        // Set narrative
        Narrative narrative = new Narrative();
        narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
        XhtmlNode div = new XhtmlNode(NodeType.Element, "div");
        div.addText("Patient derived from openEHR composition subject");
        narrative.setDiv(div);
        patient.setText(narrative);

        return patient;
    }

    /**
     * Searches the composition's content entries for the first non-null, non-PartySelf subject.
     */
    private PartyProxy findEntrySubject(Composition composition) {
        if (composition.getContent() == null) {
            return null;
        }
        for (ContentItem item : composition.getContent()) {
            PartyProxy found = findEntrySubjectInContent(item);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private PartyProxy findEntrySubjectInContent(ContentItem contentItem) {
        if (contentItem instanceof Entry entry) {
            PartyProxy subject = entry.getSubject();
            if (subject != null && !(subject instanceof PartySelf)) {
                return subject;
            }
        } else if (contentItem instanceof Section section && section.getItems() != null) {
            for (ContentItem nested : section.getItems()) {
                PartyProxy found = findEntrySubjectInContent(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Creates a FHIR CodeableConcept from an openEHR DvText/DvCodedText.
     */
    CodeableConcept createCodeableConcept(DvText dvText) {
        if (dvText == null) {
            return new CodeableConcept().setText("Unknown");
        }

        CodeableConcept concept = new CodeableConcept();
        concept.setText(dvText.getValue());

        if (dvText instanceof DvCodedText dvCodedText) {
            return createCodeableConceptFromDvCodedText(dvCodedText);
        }

        return concept;
    }

    /**
     * Creates a FHIR CodeableConcept from an openEHR DvCodedText with full coding.
     */
    CodeableConcept createCodeableConceptFromDvCodedText(DvCodedText dvCodedText) {
        CodeableConcept concept = new CodeableConcept();
        concept.setText(dvCodedText.getValue());

        CodePhrase codePhrase = dvCodedText.getDefiningCode();
        if (codePhrase != null) {
            Coding coding = new Coding();
            if (codePhrase.getTerminologyId() != null) {
                String terminologyId = codePhrase.getTerminologyId().getValue();
                coding.setSystem(mapTerminologyIdToFhirSystem(terminologyId));
            }
            coding.setCode(codePhrase.getCodeString());
            coding.setDisplay(dvCodedText.getValue());
            concept.addCoding(coding);
        }

        return concept;
    }

    /**
     * Maps an openEHR terminology ID to a FHIR system URI.
     */
    static String mapTerminologyIdToFhirSystem(String terminologyId) {
        if (terminologyId == null) {
            return null;
        }
        return switch (terminologyId.toLowerCase()) {
            case "snomed-ct", "snomed", "snomedct" -> "http://snomed.info/sct";
            case "loinc" -> "http://loinc.org";
            case "icd10", "icd-10" -> "http://hl7.org/fhir/sid/icd-10";
            case "icd10-cm", "icd-10-cm" -> "http://hl7.org/fhir/sid/icd-10-cm";
            case "icd9", "icd-9" -> "http://hl7.org/fhir/sid/icd-9-cm";
            case "rxnorm" -> "http://www.nlm.nih.gov/research/umls/rxnorm";
            case "cpt", "cpt-4" -> "http://www.ama-assn.org/go/cpt";
            case "ndc" -> "http://hl7.org/fhir/sid/ndc";
            case "cvx" -> "http://hl7.org/fhir/sid/cvx";
            case "openehr" -> "http://openehr.org/id";
            case "local" -> "http://openehr.org/local";
            default -> terminologyId;
        };
    }

    /**
     * Sets a generated narrative text on a FHIR DomainResource.
     */
    private void setNarrative(org.hl7.fhir.r4.model.DomainResource resource, DvText name) {
        Narrative narrative = new Narrative();
        narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
        XhtmlNode div = new XhtmlNode(NodeType.Element, "div");
        String text = name != null && name.getValue() != null ? name.getValue() : resource.fhirType();
        div.addText(text);
        narrative.setDiv(div);
        resource.setText(narrative);
    }

    /**
     * Adds a resource to the bundle as a collection entry.
     */
    private void addBundleEntry(Bundle bundle, Resource resource) {
        Bundle.BundleEntryComponent entry = bundle.addEntry();
        entry.setFullUrl("urn:uuid:" + resource.getId());
        entry.setResource(resource);
    }
}
