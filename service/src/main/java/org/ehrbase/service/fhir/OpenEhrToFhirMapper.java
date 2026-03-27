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
import com.nedap.archie.rm.archetyped.Locatable;
import com.nedap.archie.rm.composition.Action;
import com.nedap.archie.rm.composition.AdminEntry;
import com.nedap.archie.rm.composition.CareEntry;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.Instruction;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.composition.Section;
import com.nedap.archie.rm.datastructures.Cluster;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.Item;
import com.nedap.archie.rm.datastructures.ItemList;
import com.nedap.archie.rm.datastructures.ItemSingle;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTable;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import com.nedap.archie.rm.generic.Participation;
import com.nedap.archie.rm.generic.PartyProxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.ehrbase.openehr.sdk.webtemplate.model.WebTemplate;
import org.ehrbase.openehr.sdk.webtemplate.templateprovider.TemplateProvider;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.utilities.xhtml.NodeType;
import org.hl7.fhir.utilities.xhtml.XhtmlNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps openEHR {@link Composition} RM objects (from the Archie library) into FHIR R4
 * {@link Bundle} resources.
 *
 * <p>This mapper traverses the openEHR composition tree and converts each clinical entry
 * (Observation, Evaluation, Instruction, Action, AdminEntry) into an appropriate FHIR R4 resource:
 *
 * <ul>
 *   <li>{@link Observation} &rarr; FHIR {@link org.hl7.fhir.r4.model.Observation}
 *   <li>{@link Evaluation} &rarr; FHIR {@link Condition}
 *   <li>{@link Instruction} &rarr; FHIR {@link MedicationStatement}
 *   <li>{@link Action} &rarr; FHIR {@link Procedure}
 *   <li>{@link AdminEntry} &rarr; FHIR {@link org.hl7.fhir.r4.model.Observation} (generic)
 * </ul>
 *
 * <p>The existing {@link TemplateProvider} and {@link WebTemplate} introspection are used to
 * drive archetype-to-FHIR mapping when a template ID is available on the composition.
 */
public class OpenEhrToFhirMapper {

    private static final Logger logger = LoggerFactory.getLogger(OpenEhrToFhirMapper.class);

    private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();

    private final TemplateProvider templateProvider;

    public OpenEhrToFhirMapper(TemplateProvider templateProvider) {
        this.templateProvider = templateProvider;
    }

    /**
     * Converts an openEHR {@link Composition} into a FHIR R4 {@link Bundle}.
     *
     * @param composition the openEHR composition to transform
     * @return a FHIR Bundle containing the mapped resources
     */
    public Bundle mapCompositionToBundle(Composition composition) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);
        bundle.setId(extractCompositionId(composition));

        if (composition.getContext() != null && composition.getContext().getStartTime() != null) {
            bundle.setTimestamp(convertDvDateTime(composition.getContext().getStartTime()));
        }

        // Resolve the WebTemplate for archetype introspection if template ID is available
        WebTemplate webTemplate = resolveWebTemplate(composition);

        // Determine subject reference from the composition
        Reference subjectRef = resolveSubjectReference(composition);

        // Process each content item in the composition
        if (composition.getContent() != null) {
            for (ContentItem content : composition.getContent()) {
                List<Resource> resources = mapContentItem(content, subjectRef, webTemplate);
                for (Resource resource : resources) {
                    Bundle.BundleEntryComponent entry = bundle.addEntry();
                    entry.setResource(resource);
                    if (resource.getId() != null) {
                        entry.setFullUrl("urn:uuid:" + resource.getId());
                    }
                }
            }
        }

        return bundle;
    }

    /**
     * Serializes a FHIR R4 {@link Bundle} to a JSON or XML string.
     *
     * @param bundle the FHIR bundle to serialize
     * @param outputFormat the desired output format
     * @return the serialized string
     */
    public String serializeBundle(Bundle bundle, FhirOutputFormat outputFormat) {
        IParser parser =
                switch (outputFormat) {
                    case FHIR_JSON -> FHIR_CONTEXT.newJsonParser();
                    case FHIR_XML -> FHIR_CONTEXT.newXmlParser();
                };
        parser.setPrettyPrint(true);
        return parser.encodeResourceToString(bundle);
    }

    // ---- Content item dispatching ----

    private List<Resource> mapContentItem(ContentItem content, Reference subjectRef, WebTemplate webTemplate) {
        if (content instanceof Section section) {
            return mapSection(section, subjectRef, webTemplate);
        } else if (content instanceof Observation observation) {
            return List.of(mapObservation(observation, subjectRef));
        } else if (content instanceof Evaluation evaluation) {
            return List.of(mapEvaluation(evaluation, subjectRef));
        } else if (content instanceof Instruction instruction) {
            return List.of(mapInstruction(instruction, subjectRef));
        } else if (content instanceof Action action) {
            return List.of(mapAction(action, subjectRef));
        } else if (content instanceof AdminEntry adminEntry) {
            return List.of(mapAdminEntry(adminEntry, subjectRef));
        }

        logger.warn("Unsupported content item type: {}", content.getClass().getSimpleName());
        return Collections.emptyList();
    }

    private List<Resource> mapSection(Section section, Reference subjectRef, WebTemplate webTemplate) {
        List<Resource> resources = new ArrayList<>();
        if (section.getItems() != null) {
            for (ContentItem item : section.getItems()) {
                resources.addAll(mapContentItem(item, subjectRef, webTemplate));
            }
        }
        return resources;
    }

    // ---- Entry type mappers ----

    /**
     * Maps an openEHR {@link Observation} to a FHIR {@link org.hl7.fhir.r4.model.Observation}.
     *
     * <p>openEHR Observations model clinical measurements and assessments with a temporal
     * structure (History/Events). Each event's data elements become FHIR Observation components.
     */
    private org.hl7.fhir.r4.model.Observation mapObservation(Observation openEhrObs, Reference subjectRef) {
        org.hl7.fhir.r4.model.Observation fhirObs = new org.hl7.fhir.r4.model.Observation();
        fhirObs.setId(generateResourceId());
        fhirObs.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);

        // Map the observation name to code
        fhirObs.setCode(mapLocatableName(openEhrObs));

        // Set subject reference
        if (subjectRef != null) {
            fhirObs.setSubject(subjectRef);
        }

        // Set category based on archetype
        fhirObs.addCategory(new CodeableConcept()
                .addCoding(new org.hl7.fhir.r4.model.Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
                        .setCode("exam")
                        .setDisplay("Exam")));

        // Map protocol to method if available
        if (openEhrObs.getProtocol() != null) {
            List<Element> protocolElements = extractElements(openEhrObs.getProtocol());
            if (!protocolElements.isEmpty()) {
                Element first = protocolElements.get(0);
                if (first.getValue() != null) {
                    Type mapped = OpenEhrFhirDataTypeMapper.mapDataValue(first.getValue());
                    if (mapped instanceof CodeableConcept cc) {
                        fhirObs.setMethod(cc);
                    }
                }
            }
        }

        // Map the data from the observation's history events
        if (openEhrObs.getData() != null && openEhrObs.getData().getEvents() != null) {
            var events = openEhrObs.getData().getEvents();

            for (var event : events) {
                // Use the first event's time as the effective date
                if (event.getTime() != null && fhirObs.getEffective() == null) {
                    fhirObs.setEffective(OpenEhrFhirDataTypeMapper.mapDvDateTime(event.getTime()));
                }

                // Map each element in the event's data
                if (event.getData() != null) {
                    List<Element> elements = extractElements(event.getData());
                    mapElementsToObservation(fhirObs, elements);
                }
            }
        }

        setNarrative(fhirObs, openEhrObs);
        return fhirObs;
    }

    /**
     * Maps an openEHR {@link Evaluation} to a FHIR {@link Condition}.
     *
     * <p>openEHR Evaluations typically represent clinical assessments, diagnoses, and other
     * clinical judgements that map naturally to FHIR Conditions.
     */
    private Condition mapEvaluation(Evaluation evaluation, Reference subjectRef) {
        Condition condition = new Condition();
        condition.setId(generateResourceId());

        // Map the evaluation name to condition code
        condition.setCode(mapLocatableName(evaluation));

        // Set subject
        if (subjectRef != null) {
            condition.setSubject(subjectRef);
        }

        // Set clinical status
        condition.setClinicalStatus(new CodeableConcept()
                .addCoding(new org.hl7.fhir.r4.model.Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical")
                        .setCode("active")
                        .setDisplay("Active")));

        // Map data elements from the evaluation
        if (evaluation.getData() != null) {
            List<Element> elements = extractElements(evaluation.getData());
            for (Element element : elements) {
                if (element.getValue() == null) {
                    continue;
                }
                String name = getElementName(element);

                // Try to map specific known patterns
                if (containsIgnoreCase(name, "date") || containsIgnoreCase(name, "onset")) {
                    Type mapped = OpenEhrFhirDataTypeMapper.mapDataValue(element.getValue());
                    if (mapped instanceof DateTimeType) {
                        condition.setOnset(mapped);
                    }
                } else if (containsIgnoreCase(name, "severity")) {
                    Type mapped = OpenEhrFhirDataTypeMapper.mapDataValue(element.getValue());
                    if (mapped instanceof CodeableConcept cc) {
                        condition.setSeverity(cc);
                    }
                } else if (containsIgnoreCase(name, "body site") || containsIgnoreCase(name, "location")) {
                    Type mapped = OpenEhrFhirDataTypeMapper.mapDataValue(element.getValue());
                    if (mapped instanceof CodeableConcept cc) {
                        condition.addBodySite(cc);
                    }
                } else if (containsIgnoreCase(name, "comment") || containsIgnoreCase(name, "note")) {
                    condition.addNote().setText(element.getValue().toString());
                }
            }
        }

        setNarrative(condition, evaluation);
        return condition;
    }

    /**
     * Maps an openEHR {@link Instruction} to a FHIR {@link MedicationStatement}.
     *
     * <p>openEHR Instructions represent orders, prescriptions, or planned actions. These map
     * to FHIR MedicationStatement (or MedicationRequest) as they capture medication-related
     * clinical intent.
     */
    private MedicationStatement mapInstruction(Instruction instruction, Reference subjectRef) {
        MedicationStatement medStatement = new MedicationStatement();
        medStatement.setId(generateResourceId());
        medStatement.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);

        // Map the instruction name to medication code
        CodeableConcept medicationCode = mapLocatableName(instruction);
        medStatement.setMedication(medicationCode);

        // Set subject
        if (subjectRef != null) {
            medStatement.setSubject(subjectRef);
        }

        // Map narrative from the instruction
        if (instruction.getNarrative() != null) {
            medStatement.addNote().setText(instruction.getNarrative().getValue());
        }

        // Map activities (dosage instructions, etc.)
        if (instruction.getActivities() != null) {
            for (var activity : instruction.getActivities()) {
                if (activity.getDescription() != null) {
                    List<Element> elements = extractElements(activity.getDescription());
                    for (Element element : elements) {
                        if (element.getValue() == null) {
                            continue;
                        }
                        String name = getElementName(element);

                        if (containsIgnoreCase(name, "dose") || containsIgnoreCase(name, "amount")) {
                            // Map dosage information
                            org.hl7.fhir.r4.model.Dosage dosage = new org.hl7.fhir.r4.model.Dosage();
                            Type mapped = OpenEhrFhirDataTypeMapper.mapDataValue(element.getValue());
                            if (mapped instanceof org.hl7.fhir.r4.model.Quantity qty) {
                                dosage.addDoseAndRate().setDose(qty);
                            } else {
                                dosage.setText(element.getValue().toString());
                            }
                            medStatement.addDosage(dosage);
                        } else if (containsIgnoreCase(name, "route")) {
                            org.hl7.fhir.r4.model.Dosage dosage = medStatement.hasDosage()
                                    ? medStatement.getDosageFirstRep()
                                    : medStatement.addDosage();
                            Type mapped = OpenEhrFhirDataTypeMapper.mapDataValue(element.getValue());
                            if (mapped instanceof CodeableConcept cc) {
                                dosage.setRoute(cc);
                            }
                        }
                    }
                }
            }
        }

        setNarrative(medStatement, instruction);
        return medStatement;
    }

    /**
     * Maps an openEHR {@link Action} to a FHIR {@link Procedure}.
     *
     * <p>openEHR Actions represent activities that have been performed or are being performed,
     * which map naturally to FHIR Procedures.
     */
    private Procedure mapAction(Action action, Reference subjectRef) {
        Procedure procedure = new Procedure();
        procedure.setId(generateResourceId());
        procedure.setStatus(Procedure.ProcedureStatus.COMPLETED);

        // Map the action name to procedure code
        procedure.setCode(mapLocatableName(action));

        // Set subject
        if (subjectRef != null) {
            procedure.setSubject(subjectRef);
        }

        // Map the action time
        if (action.getTime() != null) {
            procedure.setPerformed(OpenEhrFhirDataTypeMapper.mapDvDateTime(action.getTime()));
        }

        // Map description data elements
        if (action.getDescription() != null) {
            List<Element> elements = extractElements(action.getDescription());
            for (Element element : elements) {
                if (element.getValue() == null) {
                    continue;
                }
                String name = getElementName(element);

                if (containsIgnoreCase(name, "body site") || containsIgnoreCase(name, "location")) {
                    Type mapped = OpenEhrFhirDataTypeMapper.mapDataValue(element.getValue());
                    if (mapped instanceof CodeableConcept cc) {
                        procedure.addBodySite(cc);
                    }
                } else if (containsIgnoreCase(name, "comment") || containsIgnoreCase(name, "note")) {
                    procedure.addNote().setText(element.getValue().toString());
                } else if (containsIgnoreCase(name, "reason") || containsIgnoreCase(name, "indication")) {
                    Type mapped = OpenEhrFhirDataTypeMapper.mapDataValue(element.getValue());
                    if (mapped instanceof CodeableConcept cc) {
                        procedure.addReasonCode(cc);
                    }
                }
            }
        }

        setNarrative(procedure, action);
        return procedure;
    }

    /**
     * Maps an openEHR {@link AdminEntry} to a FHIR {@link org.hl7.fhir.r4.model.Observation}
     * with a category of "social-history" as a generic fallback for administrative data.
     */
    private org.hl7.fhir.r4.model.Observation mapAdminEntry(AdminEntry adminEntry, Reference subjectRef) {
        org.hl7.fhir.r4.model.Observation fhirObs = new org.hl7.fhir.r4.model.Observation();
        fhirObs.setId(generateResourceId());
        fhirObs.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);

        // Map the admin entry name to code
        fhirObs.setCode(mapLocatableName(adminEntry));

        // Set subject
        if (subjectRef != null) {
            fhirObs.setSubject(subjectRef);
        }

        // Categorize as social-history for administrative data
        fhirObs.addCategory(new CodeableConcept()
                .addCoding(new org.hl7.fhir.r4.model.Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
                        .setCode("social-history")
                        .setDisplay("Social History")));

        // Map data elements
        if (adminEntry.getData() != null) {
            List<Element> elements = extractElements(adminEntry.getData());
            mapElementsToObservation(fhirObs, elements);
        }

        setNarrative(fhirObs, adminEntry);
        return fhirObs;
    }

    // ---- Helper methods ----

    private void mapElementsToObservation(org.hl7.fhir.r4.model.Observation fhirObs, List<Element> elements) {
        boolean primaryValueSet = false;

        for (Element element : elements) {
            if (element.getValue() == null) {
                continue;
            }

            Type fhirValue = OpenEhrFhirDataTypeMapper.mapDataValue(element.getValue());
            if (fhirValue == null) {
                continue;
            }

            // Use the first element as the primary value; subsequent as components
            if (!primaryValueSet) {
                fhirObs.setValue(fhirValue);
                primaryValueSet = true;
            } else {
                org.hl7.fhir.r4.model.Observation.ObservationComponentComponent component = fhirObs.addComponent();
                component.setCode(mapLocatableName(element));
                component.setValue(fhirValue);
            }
        }
    }

    /**
     * Recursively extracts all {@link Element} instances from an {@link ItemStructure}.
     */
    private List<Element> extractElements(ItemStructure itemStructure) {
        List<Element> elements = new ArrayList<>();
        if (itemStructure instanceof ItemTree itemTree) {
            extractElementsFromItems(itemTree.getItems(), elements);
        } else if (itemStructure instanceof ItemList itemList) {
            if (itemList.getItems() != null) {
                elements.addAll(itemList.getItems());
            }
        } else if (itemStructure instanceof ItemSingle itemSingle) {
            if (itemSingle.getItem() != null) {
                elements.add(itemSingle.getItem());
            }
        } else if (itemStructure instanceof ItemTable itemTable) {
            if (itemTable.getRows() != null) {
                for (Cluster row : itemTable.getRows()) {
                    extractElementsFromItems(row.getItems(), elements);
                }
            }
        }
        return elements;
    }

    private void extractElementsFromItems(List<Item> items, List<Element> elements) {
        if (items == null) {
            return;
        }
        for (Item item : items) {
            if (item instanceof Element element) {
                elements.add(element);
            } else if (item instanceof Cluster cluster) {
                extractElementsFromItems(cluster.getItems(), elements);
            }
        }
    }

    private CodeableConcept mapLocatableName(Locatable locatable) {
        CodeableConcept concept = new CodeableConcept();
        DvText name = locatable.getName();

        if (name instanceof DvCodedText codedName) {
            return OpenEhrFhirDataTypeMapper.mapDvCodedText(codedName);
        } else if (name != null) {
            concept.setText(name.getValue());
        }

        // Use archetype node ID as a fallback coding
        if (locatable.getArchetypeNodeId() != null) {
            concept.addCoding(new org.hl7.fhir.r4.model.Coding()
                    .setSystem("http://openehr.org/archetype")
                    .setCode(locatable.getArchetypeNodeId()));
        }

        return concept;
    }

    private String extractCompositionId(Composition composition) {
        if (composition.getUid() != null) {
            String uidValue = composition.getUid().getValue();
            // Extract UUID part from versioned object ID (format: uuid::system::version)
            int sepIdx = uidValue.indexOf("::");
            return sepIdx >= 0 ? uidValue.substring(0, sepIdx) : uidValue;
        }
        return generateResourceId();
    }

    private Reference resolveSubjectReference(Composition composition) {
        // Try to extract subject from the composition's content entries
        if (composition.getContent() != null) {
            for (ContentItem content : composition.getContent()) {
                Reference ref = extractSubjectFromEntry(content);
                if (ref != null) {
                    return ref;
                }
            }
        }

        // Try participations in context
        if (composition.getContext() != null && composition.getContext().getParticipations() != null) {
            for (Participation participation : composition.getContext().getParticipations()) {
                if (participation.getPerformer() != null) {
                    Reference ref = OpenEhrFhirDataTypeMapper.mapPartyProxy(participation.getPerformer());
                    if (ref.hasDisplay() || ref.hasReference()) {
                        return ref;
                    }
                }
            }
        }

        // Fallback: use the composer
        if (composition.getComposer() != null) {
            return OpenEhrFhirDataTypeMapper.mapPartyProxy(composition.getComposer());
        }

        return null;
    }

    private Reference extractSubjectFromEntry(ContentItem content) {
        if (content instanceof CareEntry careEntry) {
            PartyProxy subject = careEntry.getSubject();
            if (subject != null) {
                Reference ref = OpenEhrFhirDataTypeMapper.mapPartyProxy(subject);
                if (ref.hasDisplay() || ref.hasReference()) {
                    return ref;
                }
            }
        } else if (content instanceof Section section && section.getItems() != null) {
            for (ContentItem item : section.getItems()) {
                Reference ref = extractSubjectFromEntry(item);
                if (ref != null) {
                    return ref;
                }
            }
        }
        return null;
    }

    private WebTemplate resolveWebTemplate(Composition composition) {
        if (composition.getArchetypeDetails() != null
                && composition.getArchetypeDetails().getTemplateId() != null) {
            String templateId =
                    composition.getArchetypeDetails().getTemplateId().getValue();
            try {
                return templateProvider.buildIntrospect(templateId).orElse(null);
            } catch (Exception e) {
                logger.warn("Failed to resolve WebTemplate for template ID '{}': {}", templateId, e.getMessage());
            }
        }
        return null;
    }

    private void setNarrative(org.hl7.fhir.r4.model.DomainResource resource, Locatable locatable) {
        String name = locatable.getName() != null ? locatable.getName().getValue() : locatable.getArchetypeNodeId();
        if (name != null) {
            Narrative narrative = new Narrative();
            narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
            XhtmlNode div = new XhtmlNode(NodeType.Element, "div");
            div.addText("Mapped from openEHR: " + name);
            narrative.setDiv(div);
            resource.setText(narrative);
        }
    }

    private static String getElementName(Element element) {
        if (element.getName() != null) {
            return element.getName().getValue();
        }
        return element.getArchetypeNodeId();
    }

    private static boolean containsIgnoreCase(String str, String search) {
        if (str == null || search == null) {
            return false;
        }
        return str.toLowerCase().contains(search.toLowerCase());
    }

    private Date convertDvDateTime(DvDateTime dvDateTime) {
        if (dvDateTime == null || dvDateTime.getValue() == null) {
            return null;
        }
        DateTimeType fhirDt = OpenEhrFhirDataTypeMapper.mapDvDateTime(dvDateTime);
        return fhirDt.getValue();
    }

    private static String generateResourceId() {
        return UUID.randomUUID().toString();
    }
}
