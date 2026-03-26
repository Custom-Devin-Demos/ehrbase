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
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.EventContext;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.Item;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import com.nedap.archie.rm.generic.PartyIdentified;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.ArchetypeID;
import com.nedap.archie.rm.support.identification.TerminologyId;
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
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps between openEHR Compositions containing EVALUATION entries and FHIR Condition resources.
 * Uses configuration-driven template mappings to handle template-specific field mappings.
 */
@Component
public class ConditionFhirMapper {

    private static final Logger LOG = LoggerFactory.getLogger(ConditionFhirMapper.class);
    private static final String SNOMED_SYSTEM = "http://snomed.info/sct";
    private static final String CONDITION_CATEGORY_SYSTEM =
            "http://terminology.hl7.org/CodeSystem/condition-category";

    private final TemplateMappingRegistry mappingRegistry;

    public ConditionFhirMapper(TemplateMappingRegistry mappingRegistry) {
        this.mappingRegistry = mappingRegistry;
    }

    /**
     * Maps an openEHR Composition with EVALUATION entries to a FHIR Condition.
     */
    public Condition toCondition(UUID ehrId, Composition composition, String templateId) {
        Condition condition = new Condition();

        // Set ID from composition UID
        if (composition.getUid() != null) {
            condition.setId(composition.getUid().getRoot().getValue());
        }

        // Set subject reference to the EHR
        condition.setSubject(new Reference("Patient/" + ehrId.toString()));

        // Set clinical status
        condition.setClinicalStatus(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical")
                        .setCode("active")
                        .setDisplay("Active")));

        // Set verification status
        condition.setVerificationStatus(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/condition-ver-status")
                        .setCode("confirmed")
                        .setDisplay("Confirmed")));

        // Map recorded date from composition context
        if (composition.getContext() != null && composition.getContext().getStartTime() != null) {
            TemporalAccessor temporal =
                    composition.getContext().getStartTime().getValue();
            if (temporal instanceof OffsetDateTime startTime) {
                condition.setRecordedDate(Date.from(startTime.toInstant()));
            }
        }

        // Apply template-specific mappings
        TemplateMappingDefinition mapping = mappingRegistry.getMapping(templateId).orElse(null);

        if (mapping != null) {
            applyMappingToCondition(condition, composition, mapping);
        } else {
            applyDefaultConditionMapping(condition, composition);
        }

        return condition;
    }

    /**
     * Maps a FHIR Condition to an openEHR Composition.
     */
    public Composition toComposition(Condition condition, String templateId) {
        Composition composition = new Composition();

        // Set archetype details
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("FHIR Condition"));
        composition.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        composition.setTerritory(new CodePhrase(new TerminologyId("ISO_3166-1"), "US"));
        composition.setCategory(
                new DvCodedText("event", new CodePhrase(new TerminologyId("openehr"), "433")));
        composition.setComposer(new PartyIdentified(null, "FHIR Import", null));
        composition.setArchetypeDetails(
                new Archetyped(new ArchetypeID("openEHR-EHR-COMPOSITION.encounter.v1"), "1.1.0"));

        // Set context with recorded date
        EventContext context = new EventContext();
        if (condition.hasRecordedDate()) {
            context.setStartTime(new DvDateTime(
                    OffsetDateTime.ofInstant(condition.getRecordedDate().toInstant(), ZoneOffset.UTC)));
        } else if (condition.hasOnsetDateTimeType()
                && condition.getOnsetDateTimeType().getValue() != null) {
            context.setStartTime(new DvDateTime(OffsetDateTime.ofInstant(
                    condition.getOnsetDateTimeType().getValue().toInstant(), ZoneOffset.UTC)));
        } else {
            context.setStartTime(new DvDateTime(OffsetDateTime.now(ZoneOffset.UTC)));
        }
        context.setSetting(
                new DvCodedText("other care", new CodePhrase(new TerminologyId("openehr"), "238")));
        composition.setContext(context);

        // Build EVALUATION entry
        TemplateMappingDefinition mapping = mappingRegistry.getMapping(templateId).orElse(null);

        List<ContentItem> content = new ArrayList<>();
        if (mapping != null) {
            content.add(buildEvaluationFromMapping(condition, mapping));
        } else {
            content.add(buildDefaultEvaluation(condition));
        }
        composition.setContent(content);

        return composition;
    }

    private void applyMappingToCondition(
            Condition condition, Composition composition, TemplateMappingDefinition mapping) {

        // Set category from mapping definition
        if (mapping.getCategorySystem() != null && mapping.getCategoryCode() != null) {
            condition.addCategory(new CodeableConcept()
                    .addCoding(new Coding()
                            .setSystem(mapping.getCategorySystem())
                            .setCode(mapping.getCategoryCode())
                            .setDisplay(mapping.getCategoryDisplay())));
        }

        // Extract values from composition content based on field mappings
        if (mapping.getFieldMappings() != null) {
            for (ContentItem item : safeContent(composition)) {
                if (item instanceof Evaluation eval) {
                    mapEvaluationFields(condition, eval, mapping.getFieldMappings());
                }
            }
        }
    }

    private void mapEvaluationFields(
            Condition condition,
            Evaluation evaluation,
            List<TemplateMappingDefinition.FieldMapping> fieldMappings) {

        ItemStructure data = evaluation.getData();
        if (!(data instanceof ItemTree itemTree)) {
            return;
        }

        for (TemplateMappingDefinition.FieldMapping fieldMapping : fieldMappings) {
            mapSingleConditionField(condition, itemTree, fieldMapping);
        }
    }

    private void mapSingleConditionField(
            Condition condition,
            ItemTree itemTree,
            TemplateMappingDefinition.FieldMapping fieldMapping) {

        String fhirPath = fieldMapping.getFhirPath();
        String nodeId = extractNodeId(fieldMapping.getArchetypePath());
        Element element = findElementByNodeId(itemTree, nodeId);

        if (element == null) {
            return;
        }

        if ("CodeableConcept".equals(fieldMapping.getType())) {
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

            switch (fhirPath) {
                case "code" -> condition.setCode(concept);
                case "severity" -> condition.setSeverity(concept);
                case "bodySite" -> condition.addBodySite(concept);
                default -> LOG.debug("Unmapped FHIR path for Condition: {}", fhirPath);
            }
        } else if ("DateTime".equals(fieldMapping.getType())
                && element.getValue() instanceof DvDateTime dvDateTime) {
            if ("onsetDateTime".equals(fhirPath)
                    && dvDateTime.getValue() instanceof OffsetDateTime onsetOdt) {
                condition.setOnset(new DateTimeType(Date.from(onsetOdt.toInstant())));
            }
        }
    }

    private void applyDefaultConditionMapping(Condition condition, Composition composition) {
        for (ContentItem item : safeContent(composition)) {
            if (item instanceof Evaluation eval) {
                // Set code from evaluation name
                if (eval.getName() != null) {
                    condition.setCode(new CodeableConcept().setText(eval.getName().getValue()));
                }

                // Try to extract coded text from data
                if (eval.getData() instanceof ItemTree itemTree && itemTree.getItems() != null) {
                    for (var treeItem : itemTree.getItems()) {
                        if (treeItem instanceof Element el) {
                            if (el.getValue() instanceof DvCodedText dvCoded) {
                                condition.setCode(new CodeableConcept()
                                        .addCoding(new Coding()
                                                .setSystem(dvCoded.getDefiningCode()
                                                        .getTerminologyId()
                                                        .getValue())
                                                .setCode(dvCoded
                                                        .getDefiningCode()
                                                        .getCodeString())
                                                .setDisplay(dvCoded.getValue())));
                                break;
                            } else if (el.getValue() instanceof DvText dvText) {
                                condition.setCode(new CodeableConcept().setText(dvText.getValue()));
                                break;
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    private Evaluation buildEvaluationFromMapping(
            Condition condition, TemplateMappingDefinition mapping) {

        Evaluation eval = new Evaluation();
        eval.setArchetypeNodeId("openEHR-EHR-EVALUATION.mapping_import.v1");
        eval.setName(new DvText(
                mapping.getDescription() != null ? mapping.getDescription() : "Mapped Condition"));
        eval.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        eval.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        eval.setSubject(new PartySelf());

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0001");
        itemTree.setName(new DvText("structure"));

        List<Item> elements = new ArrayList<>();

        if (mapping.getFieldMappings() != null) {
            for (TemplateMappingDefinition.FieldMapping fieldMapping : mapping.getFieldMappings()) {
                Element element = mapFhirConditionFieldToElement(condition, fieldMapping);
                if (element != null) {
                    elements.add(element);
                }
            }
        }

        itemTree.setItems(elements);
        eval.setData(itemTree);

        return eval;
    }

    private Evaluation buildDefaultEvaluation(Condition condition) {
        Evaluation eval = new Evaluation();
        eval.setArchetypeNodeId("openEHR-EHR-EVALUATION.default_import.v1");
        eval.setName(new DvText("Condition"));
        eval.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        eval.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        eval.setSubject(new PartySelf());

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0001");
        itemTree.setName(new DvText("structure"));

        List<Item> elements = new ArrayList<>();

        // Map condition code to problem/diagnosis element
        if (condition.hasCode()) {
            Element el = new Element();
            el.setArchetypeNodeId("at0002");
            el.setName(new DvText("Problem/Diagnosis"));

            if (condition.getCode().hasCoding()) {
                Coding coding = condition.getCode().getCodingFirstRep();
                el.setValue(new DvCodedText(
                        coding.getDisplay() != null ? coding.getDisplay() : coding.getCode(),
                        new CodePhrase(
                                new TerminologyId(coding.getSystem() != null ? coding.getSystem() : SNOMED_SYSTEM),
                                coding.getCode())));
            } else {
                el.setValue(new DvText(condition.getCode().getText()));
            }
            elements.add(el);
        }

        itemTree.setItems(elements);
        eval.setData(itemTree);

        return eval;
    }

    private Element mapFhirConditionFieldToElement(
            Condition condition, TemplateMappingDefinition.FieldMapping fieldMapping) {

        String fhirPath = fieldMapping.getFhirPath();
        String nodeId = extractNodeId(fieldMapping.getArchetypePath());

        if ("CodeableConcept".equals(fieldMapping.getType())) {
            CodeableConcept concept = null;

            switch (fhirPath) {
                case "code" -> concept = condition.hasCode() ? condition.getCode() : null;
                case "severity" -> concept = condition.hasSeverity() ? condition.getSeverity() : null;
                case "bodySite" -> concept =
                        condition.hasBodySite() ? condition.getBodySiteFirstRep() : null;
                default -> {
                    return null;
                }
            }

            if (concept != null) {
                Element element = new Element();
                element.setArchetypeNodeId(nodeId);
                Map<String, String> codingDef = fieldMapping.getCoding();
                element.setName(new DvText(codingDef != null
                        ? codingDef.getOrDefault("display", "Value")
                        : "Value"));

                if (concept.hasCoding()) {
                    Coding coding = concept.getCodingFirstRep();
                    element.setValue(new DvCodedText(
                            coding.getDisplay() != null ? coding.getDisplay() : coding.getCode(),
                            new CodePhrase(
                                    new TerminologyId(
                                            coding.getSystem() != null ? coding.getSystem() : SNOMED_SYSTEM),
                                    coding.getCode())));
                } else if (concept.hasText()) {
                    element.setValue(new DvText(concept.getText()));
                }
                return element;
            }
        } else if ("DateTime".equals(fieldMapping.getType()) && "onsetDateTime".equals(fhirPath)) {
            if (condition.hasOnsetDateTimeType()
                    && condition.getOnsetDateTimeType().getValue() != null) {
                Element element = new Element();
                element.setArchetypeNodeId(nodeId);
                element.setName(new DvText("Onset"));
                element.setValue(new DvDateTime(OffsetDateTime.ofInstant(
                        condition.getOnsetDateTimeType().getValue().toInstant(), ZoneOffset.UTC)));

                return element;
            }
        }

        return null;
    }

    private String extractNodeId(String archetypePath) {
        if (archetypePath == null) {
            return "at0002";
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

    private List<ContentItem> safeContent(Composition composition) {
        return composition.getContent() != null ? composition.getContent() : List.of();
    }
}
