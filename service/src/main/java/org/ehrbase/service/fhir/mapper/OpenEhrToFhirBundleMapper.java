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
package org.ehrbase.service.fhir.mapper;

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.composition.EventContext;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.generic.PartyIdentified;
import com.nedap.archie.rm.generic.PartyProxy;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the mapping of a complete openEHR {@link Composition} into a FHIR R4 {@link Bundle}.
 *
 * <p>The mapper walks the composition's content tree, delegating to specialized
 * {@link ContentItemFhirMapper} implementations for each entry type (Observation, Evaluation,
 * Instruction, Action). Sections are recursively traversed. The resulting FHIR resources are
 * collected into a transaction {@link Bundle}.
 *
 * <p>An {@link Encounter} resource is created from the composition's {@link EventContext} when
 * present, providing clinical context for the bundled resources.
 */
@Component
public class OpenEhrToFhirBundleMapper {

    private final List<ContentItemFhirMapper> mappers;

    public OpenEhrToFhirBundleMapper(
            ObservationFhirMapper observationMapper,
            EvaluationFhirMapper evaluationMapper,
            InstructionFhirMapper instructionMapper,
            ActionFhirMapper actionMapper,
            SectionFhirMapper sectionMapper) {
        this.mappers = List.of(sectionMapper, observationMapper, evaluationMapper, instructionMapper, actionMapper);
    }

    /**
     * Maps an openEHR Composition into a FHIR R4 Bundle.
     *
     * @param ehrId       the EHR identifier (used as logical Patient reference)
     * @param composition the openEHR Composition RM object
     * @return a FHIR R4 transaction Bundle containing the mapped resources
     */
    public Bundle map(UUID ehrId, Composition composition) {
        String ehrIdStr = ehrId.toString();

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);
        bundle.setTimestamp(new Date());

        Meta meta = new Meta();
        meta.addProfile("http://hl7.org/fhir/StructureDefinition/Bundle");
        bundle.setMeta(meta);

        // Add composition identifier
        if (composition.getUid() != null) {
            bundle.setIdentifier(new Identifier()
                    .setSystem("urn:ehrbase:composition")
                    .setValue(composition.getUid().getValue()));
        }

        // Create an Encounter from the composition context if present
        Encounter encounter = mapEventContext(composition, ehrIdStr);
        if (encounter != null) {
            addResourceToBundle(bundle, encounter, "Encounter");
        }

        // Walk the composition content tree and map entries
        if (composition.getContent() != null) {
            for (ContentItem contentItem : composition.getContent()) {
                List<Resource> resources = mapContentItem(contentItem, ehrIdStr);
                for (Resource resource : resources) {
                    // Link each resource to the encounter if present
                    if (encounter != null) {
                        linkToEncounter(resource, encounter);
                    }
                    addResourceToBundle(bundle, resource, resource.fhirType());
                }
            }
        }

        return bundle;
    }

    private List<Resource> mapContentItem(ContentItem contentItem, String ehrId) {
        for (ContentItemFhirMapper mapper : mappers) {
            if (mapper.supports(contentItem)) {
                return mapper.map(contentItem, ehrId);
            }
        }
        return List.of();
    }

    private Encounter mapEventContext(Composition composition, String ehrId) {
        EventContext context = composition.getContext();
        if (context == null) {
            return null;
        }

        Encounter encounter = new Encounter();
        encounter.setStatus(Encounter.EncounterStatus.FINISHED);
        encounter.setSubject(new Reference("Patient/" + ehrId));

        // Map setting
        if (context.getSetting() != null) {
            encounter.addType(dvTextToCodeableConcept(context.getSetting()));
        }

        // Map class from healthcare facility
        encounter.setClass_(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")
                .setCode("AMB")
                .setDisplay("ambulatory"));

        // Map time period
        if (context.getStartTime() != null) {
            Period period = new Period();
            if (context.getStartTime().getValue() != null) {
                period.setStartElement(new org.hl7.fhir.r4.model.DateTimeType(
                        context.getStartTime().getValue().toString()));
            }
            if (context.getEndTime() != null && context.getEndTime().getValue() != null) {
                period.setEndElement(new org.hl7.fhir.r4.model.DateTimeType(
                        context.getEndTime().getValue().toString()));
            }
            encounter.setPeriod(period);
        }

        // Map healthcare facility as service provider
        if (context.getHealthCareFacility() != null
                && context.getHealthCareFacility().getName() != null) {
            encounter.setServiceProvider(
                    new Reference().setDisplay(context.getHealthCareFacility().getName()));
        }

        // Map participants
        if (context.getParticipations() != null) {
            for (var participation : context.getParticipations()) {
                Encounter.EncounterParticipantComponent participant = new Encounter.EncounterParticipantComponent();
                if (participation.getFunction() != null) {
                    participant.addType(dvTextToCodeableConcept(participation.getFunction()));
                }
                PartyProxy performer = participation.getPerformer();
                if (performer instanceof PartyIdentified partyIdentified && partyIdentified.getName() != null) {
                    participant.setIndividual(new Reference().setDisplay(partyIdentified.getName()));
                }
                encounter.addParticipant(participant);
            }
        }

        return encounter;
    }

    private CodeableConcept dvTextToCodeableConcept(DvText dvText) {
        return ObservationFhirMapper.dvTextToCodeableConcept(dvText);
    }

    private void linkToEncounter(Resource resource, Encounter encounter) {
        Reference encounterRef = new Reference("Encounter/" + encounter.getId());
        if (resource instanceof org.hl7.fhir.r4.model.Observation obs) {
            obs.setEncounter(encounterRef);
        } else if (resource instanceof org.hl7.fhir.r4.model.Condition cond) {
            cond.setEncounter(encounterRef);
        } else if (resource instanceof org.hl7.fhir.r4.model.Procedure proc) {
            proc.setEncounter(encounterRef);
        }
        // MedicationStatement does not have a direct encounter reference in R4
    }

    private void addResourceToBundle(Bundle bundle, Resource resource, String resourceType) {
        // Assign a unique ID if not set
        if (resource.getId() == null || resource.getId().isEmpty()) {
            resource.setId(UUID.randomUUID().toString());
        }

        Bundle.BundleEntryComponent entry = bundle.addEntry();
        entry.setFullUrl("urn:uuid:" + resource.getId());
        entry.setResource(resource);

        Bundle.BundleEntryRequestComponent request = new Bundle.BundleEntryRequestComponent();
        request.setMethod(Bundle.HTTPVerb.POST);
        request.setUrl(resourceType);
        entry.setRequest(request);
    }
}
