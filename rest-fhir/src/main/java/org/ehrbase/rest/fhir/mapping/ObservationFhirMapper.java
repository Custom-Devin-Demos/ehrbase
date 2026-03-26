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

import com.nedap.archie.rm.archetyped.Archetyped;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.composition.EventContext;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.Event;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.Item;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datastructures.PointEvent;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import com.nedap.archie.rm.generic.PartyIdentified;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.ArchetypeID;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps between openEHR Compositions containing OBSERVATION entries and FHIR Observation resources.
 * Uses configuration-driven template mappings to handle template-specific field mappings.
 */
@Component
public class ObservationFhirMapper {

    private static final Logger LOG = LoggerFactory.getLogger(ObservationFhirMapper.class);
    private static final String UCUM_SYSTEM = "http://unitsofmeasure.org";
    private static final String LOINC_SYSTEM = "http://loinc.org";
    private static final String SNOMED_SYSTEM = "http://snomed.info/sct";

    private final TemplateMappingRegistry mappingRegistry;

    public ObservationFhirMapper(TemplateMappingRegistry mappingRegistry) {
        this.mappingRegistry = mappingRegistry;
    }

    /**
     * Maps an openEHR Composition with OBSERVATION entries to a FHIR Observation.
     */
    public org.hl7.fhir.r4.model.Observation toObservation(UUID ehrId, Composition composition, String templateId) {
        org.hl7.fhir.r4.model.Observation fhirObs = new org.hl7.fhir.r4.model.Observation();

        // Set ID from composition UID
        if (composition.getUid() != null) {
            fhirObs.setId(composition.getUid().getRoot().getValue());
        }

        // Set status
        fhirObs.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);

        // Set subject reference to the EHR
        fhirObs.setSubject(new Reference("Patient/" + ehrId.toString()));

        // Map effective date from composition context
        if (composition.getContext() != null && composition.getContext().getStartTime() != null) {
            TemporalAccessor temporal =
                    composition.getContext().getStartTime().getValue();
            if (temporal instanceof OffsetDateTime startTime) {
                fhirObs.setEffective(new DateTimeType(Date.from(startTime.toInstant())));
            }
        }

        // Apply template-specific mappings
        TemplateMappingDefinition mapping = mappingRegistry.getMapping(templateId).orElse(null);

        if (mapping != null) {
            applyMappingToObservation(fhirObs, composition, mapping);
        } else {
            applyDefaultObservationMapping(fhirObs, composition);
        }

        return fhirObs;
    }

    /**
     * Maps a FHIR Observation to an openEHR Composition.
     */
    public Composition toComposition(org.hl7.fhir.r4.model.Observation fhirObs, String templateId) {
        Composition composition = new Composition();

        // Set archetype details
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("FHIR Observation"));
        composition.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        composition.setTerritory(new CodePhrase(new TerminologyId("ISO_3166-1"), "US"));
        composition.setCategory(
                new DvCodedText("event", new CodePhrase(new TerminologyId("openehr"), "433")));
        composition.setComposer(new PartyIdentified(null, "FHIR Import", null));
        composition.setArchetypeDetails(
                new Archetyped(new ArchetypeID("openEHR-EHR-COMPOSITION.encounter.v1"), "1.1.0"));

        // Set context with effective date
        EventContext context = new EventContext();
        if (fhirObs.hasEffectiveDateTimeType()) {
            Date effective = fhirObs.getEffectiveDateTimeType().getValue();
            if (effective != null) {
                context.setStartTime(
                        new DvDateTime(OffsetDateTime.ofInstant(effective.toInstant(), ZoneOffset.UTC)));
            }
        } else {
            context.setStartTime(new DvDateTime(OffsetDateTime.now(ZoneOffset.UTC)));
        }
        context.setSetting(
                new DvCodedText("other care", new CodePhrase(new TerminologyId("openehr"), "238")));
        composition.setContext(context);

        // Apply template-specific mappings
        TemplateMappingDefinition mapping = mappingRegistry.getMapping(templateId).orElse(null);

        List<ContentItem> content = new ArrayList<>();
        if (mapping != null) {
            content.add(buildObservationFromMapping(fhirObs, mapping));
        } else {
            content.add(buildDefaultObservation(fhirObs));
        }
        composition.setContent(content);

        return composition;
    }

    private void applyMappingToObservation(
            org.hl7.fhir.r4.model.Observation fhirObs,
            Composition composition,
            TemplateMappingDefinition mapping) {

        // Set code from mapping definition
        if (mapping.getCodingSystem() != null && mapping.getCodingCode() != null) {
            fhirObs.setCode(new CodeableConcept()
                    .addCoding(new Coding()
                            .setSystem(mapping.getCodingSystem())
                            .setCode(mapping.getCodingCode())
                            .setDisplay(mapping.getCodingDisplay())));
        }

        // Set category from mapping definition
        if (mapping.getCategorySystem() != null && mapping.getCategoryCode() != null) {
            fhirObs.addCategory(new CodeableConcept()
                    .addCoding(new Coding()
                            .setSystem(mapping.getCategorySystem())
                            .setCode(mapping.getCategoryCode())
                            .setDisplay(mapping.getCategoryDisplay())));
        }

        // Extract values from composition content based on field mappings
        if (mapping.getFieldMappings() != null) {
            for (ContentItem item : safeContent(composition)) {
                if (item instanceof Observation obs) {
                    mapObservationFields(fhirObs, obs, mapping.getFieldMappings());
                }
            }
        }
    }

    private void mapObservationFields(
            org.hl7.fhir.r4.model.Observation fhirObs,
            Observation openEhrObs,
            List<TemplateMappingDefinition.FieldMapping> fieldMappings) {

        if (openEhrObs.getData() == null) {
            return;
        }

        History<?> history = openEhrObs.getData();
        for (Event<?> event : safeEvents(history)) {
            ItemStructure data = event.getData();
            if (data instanceof ItemTree itemTree) {
                for (TemplateMappingDefinition.FieldMapping fieldMapping : fieldMappings) {
                    mapSingleField(fhirObs, itemTree, fieldMapping);
                }
            }
        }
    }

    private void mapSingleField(
            org.hl7.fhir.r4.model.Observation fhirObs,
            ItemTree itemTree,
            TemplateMappingDefinition.FieldMapping fieldMapping) {

        String fhirPath = fieldMapping.getFhirPath();
        String archetypePath = fieldMapping.getArchetypePath();

        // Find the element by archetype node ID (last segment of the path)
        String nodeId = extractNodeId(archetypePath);
        Element element = findElementByNodeId(itemTree, nodeId);

        if (element == null) {
            return;
        }

        if ("Quantity".equals(fieldMapping.getType()) && element.getValue() instanceof DvQuantity dvQuantity) {
            Quantity fhirQuantity = new Quantity();
            fhirQuantity.setValue(BigDecimal.valueOf(dvQuantity.getMagnitude()));
            fhirQuantity.setUnit(fieldMapping.getUcumUnit() != null ? fieldMapping.getUcumUnit() : dvQuantity.getUnits());
            fhirQuantity.setSystem(UCUM_SYSTEM);
            fhirQuantity.setCode(fieldMapping.getUcumUnit() != null ? fieldMapping.getUcumUnit() : dvQuantity.getUnits());

            if ("valueQuantity".equals(fhirPath)) {
                fhirObs.setValue(fhirQuantity);
            } else if (fhirPath.startsWith("component")) {
                org.hl7.fhir.r4.model.Observation.ObservationComponentComponent component =
                        new org.hl7.fhir.r4.model.Observation.ObservationComponentComponent();

                Map<String, String> coding = fieldMapping.getCoding();
                if (coding != null) {
                    component.setCode(new CodeableConcept()
                            .addCoding(new Coding()
                                    .setSystem(coding.get("system"))
                                    .setCode(coding.get("code"))
                                    .setDisplay(coding.get("display"))));
                }
                component.setValue(fhirQuantity);
                fhirObs.addComponent(component);
            }
        } else if ("CodeableConcept".equals(fieldMapping.getType())) {
            CodeableConcept concept = new CodeableConcept();
            String system = fieldMapping.getSystem() != null ? fieldMapping.getSystem() : SNOMED_SYSTEM;

            if (element.getValue() instanceof DvCodedText dvCodedText) {
                concept.addCoding(new Coding()
                        .setSystem(system)
                        .setCode(dvCodedText.getDefiningCode().getCodeString())
                        .setDisplay(dvCodedText.getValue()));
            } else if (element.getValue() instanceof DvText dvText) {
                concept.setText(dvText.getValue());
            }

            if ("valueCodeableConcept".equals(fhirPath)) {
                fhirObs.setValue(concept);
            }
        }
    }

    private void applyDefaultObservationMapping(
            org.hl7.fhir.r4.model.Observation fhirObs, Composition composition) {
        // Default mapping: extract the first Observation entry's data
        for (ContentItem item : safeContent(composition)) {
            if (item instanceof Observation obs) {
                // Set code from observation name
                if (obs.getName() != null) {
                    fhirObs.setCode(new CodeableConcept().setText(obs.getName().getValue()));
                }

                // Extract first quantity value
                if (obs.getData() != null) {
                    History<?> history = obs.getData();
                    for (Event<?> event : safeEvents(history)) {
                        if (event.getData() instanceof ItemTree itemTree && itemTree.getItems() != null) {
                            for (var item2 : itemTree.getItems()) {
                                if (item2 instanceof Element el && el.getValue() instanceof DvQuantity dv) {
                                    Quantity quantity = new Quantity();
                                    quantity.setValue(BigDecimal.valueOf(dv.getMagnitude()));
                                    quantity.setUnit(dv.getUnits());
                                    quantity.setSystem(UCUM_SYSTEM);
                                    fhirObs.setValue(quantity);
                                    return;
                                }
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    private Observation buildObservationFromMapping(
            org.hl7.fhir.r4.model.Observation fhirObs, TemplateMappingDefinition mapping) {

        Observation obs = new Observation();
        obs.setArchetypeNodeId("openEHR-EHR-OBSERVATION.mapping_import.v1");
        obs.setName(new DvText(
                mapping.getCodingDisplay() != null ? mapping.getCodingDisplay() : "Mapped Observation"));
        obs.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        obs.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        obs.setSubject(new PartySelf());

        // Build data history
        History<ItemStructure> history = new History<>();
        history.setArchetypeNodeId("at0001");
        history.setName(new DvText("History"));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0002");
        event.setName(new DvText("Any event"));

        // Set time from FHIR effective
        if (fhirObs.hasEffectiveDateTimeType() && fhirObs.getEffectiveDateTimeType().getValue() != null) {
            event.setTime(new DvDateTime(
                    OffsetDateTime.ofInstant(
                            fhirObs.getEffectiveDateTimeType().getValue().toInstant(), ZoneOffset.UTC)));
        } else {
            event.setTime(new DvDateTime(OffsetDateTime.now(ZoneOffset.UTC)));
        }

        history.setOrigin(event.getTime());

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0003");
        itemTree.setName(new DvText("Tree"));

        List<Item> elements = new ArrayList<>();

        // Map FHIR fields back to openEHR elements using mapping definition
        if (mapping.getFieldMappings() != null) {
            for (TemplateMappingDefinition.FieldMapping fieldMapping : mapping.getFieldMappings()) {
                Element element = mapFhirFieldToElement(fhirObs, fieldMapping);
                if (element != null) {
                    elements.add(element);
                }
            }
        }

        itemTree.setItems(elements);
        event.setData(itemTree);
        history.setEvents(List.of(event));
        obs.setData(history);

        return obs;
    }

    private Observation buildDefaultObservation(org.hl7.fhir.r4.model.Observation fhirObs) {
        Observation obs = new Observation();
        obs.setArchetypeNodeId("openEHR-EHR-OBSERVATION.default_import.v1");
        obs.setName(new DvText(
                fhirObs.hasCode() && fhirObs.getCode().hasText()
                        ? fhirObs.getCode().getText()
                        : "Observation"));
        obs.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        obs.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        obs.setSubject(new PartySelf());

        History<ItemStructure> history = new History<>();
        history.setArchetypeNodeId("at0001");
        history.setName(new DvText("History"));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0002");
        event.setName(new DvText("Any event"));
        event.setTime(new DvDateTime(OffsetDateTime.now(ZoneOffset.UTC)));
        history.setOrigin(event.getTime());

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0003");
        itemTree.setName(new DvText("Tree"));

        List<Item> elements = new ArrayList<>();

        // Map value
        if (fhirObs.hasValueQuantity()) {
            Quantity q = fhirObs.getValueQuantity();
            Element el = new Element();
            el.setArchetypeNodeId("at0004");
            el.setName(new DvText("Value"));
            el.setValue(new DvQuantity(
                    q.getUnit(), q.getValue().doubleValue(), 2L));
            elements.add(el);
        }

        itemTree.setItems(elements);
        event.setData(itemTree);
        history.setEvents(List.of(event));
        obs.setData(history);

        return obs;
    }

    private Element mapFhirFieldToElement(
            org.hl7.fhir.r4.model.Observation fhirObs,
            TemplateMappingDefinition.FieldMapping fieldMapping) {

        String fhirPath = fieldMapping.getFhirPath();
        String nodeId = extractNodeId(fieldMapping.getArchetypePath());

        if ("Quantity".equals(fieldMapping.getType())) {
            Quantity fhirQuantity = null;

            if ("valueQuantity".equals(fhirPath) && fhirObs.hasValueQuantity()) {
                fhirQuantity = fhirObs.getValueQuantity();
            } else if (fhirPath.startsWith("component") && fieldMapping.getCoding() != null) {
                fhirQuantity = findComponentValue(fhirObs, fieldMapping.getCoding());
            }

            if (fhirQuantity != null && fhirQuantity.getValue() != null) {
                Element element = new Element();
                element.setArchetypeNodeId(nodeId);
                element.setName(new DvText(fieldMapping.getCoding() != null
                        ? fieldMapping.getCoding().getOrDefault("display", "Value")
                        : "Value"));
                String unit = fieldMapping.getUnit() != null ? fieldMapping.getUnit() : fhirQuantity.getUnit();
                element.setValue(new DvQuantity(
                        unit, fhirQuantity.getValue().doubleValue(), 2L));
                return element;
            }
        }

        return null;
    }

    private Quantity findComponentValue(
            org.hl7.fhir.r4.model.Observation fhirObs, Map<String, String> coding) {
        if (!fhirObs.hasComponent() || coding == null) {
            return null;
        }

        String targetCode = coding.get("code");
        for (org.hl7.fhir.r4.model.Observation.ObservationComponentComponent component : fhirObs.getComponent()) {
            if (component.hasCode()) {
                for (Coding c : component.getCode().getCoding()) {
                    if (targetCode != null && targetCode.equals(c.getCode())) {
                        return component.hasValueQuantity() ? component.getValueQuantity() : null;
                    }
                }
            }
        }
        return null;
    }

    private String extractNodeId(String archetypePath) {
        if (archetypePath == null) {
            return "at0004";
        }
        int lastBracket = archetypePath.lastIndexOf('[');
        int closeBracket = archetypePath.lastIndexOf(']');
        if (lastBracket >= 0 && closeBracket > lastBracket) {
            return archetypePath.substring(lastBracket + 1, closeBracket);
        }
        return archetypePath;
    }

    private Element findElementByNodeId(ItemTree itemTree, String nodeId) {
        if (itemTree.getItems() == null) {
            return null;
        }
        for (var item : itemTree.getItems()) {
            if (item instanceof Element el && nodeId.equals(el.getArchetypeNodeId())) {
                return el;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Event<?>> safeEvents(History<?> history) {
        List<?> events = history.getEvents();
        return events != null ? (List<Event<?>>) events : List.of();
    }

    private List<ContentItem> safeContent(Composition composition) {
        return composition.getContent() != null ? composition.getContent() : List.of();
    }
}
