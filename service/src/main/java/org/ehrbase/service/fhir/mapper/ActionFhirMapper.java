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

import static org.ehrbase.service.fhir.mapper.ObservationFhirMapper.dvDateTimeToFhir;
import static org.ehrbase.service.fhir.mapper.ObservationFhirMapper.dvTextToCodeableConcept;
import static org.ehrbase.service.fhir.mapper.ObservationFhirMapper.extractElements;

import com.nedap.archie.rm.composition.Action;
import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import java.util.Collections;
import java.util.List;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Maps openEHR {@link Action} entries to FHIR R4 {@link Procedure} resources.
 * Actions in openEHR represent clinical activities that have been performed,
 * such as procedures, medication administrations, and other completed actions.
 */
@Component
public class ActionFhirMapper implements ContentItemFhirMapper {

    @Override
    public boolean supports(ContentItem item) {
        return item instanceof Action;
    }

    @Override
    public List<Resource> map(ContentItem item, String ehrId) {
        if (!(item instanceof Action action)) {
            return Collections.emptyList();
        }

        Procedure procedure = new Procedure();
        procedure.setStatus(mapIsmTransitionToStatus(action));
        procedure.setSubject(new Reference("Patient/" + ehrId));

        // Map the action name as the procedure code
        DvText name = action.getName();
        procedure.setCode(dvTextToCodeableConcept(name));

        // Map action time
        if (action.getTime() != null) {
            procedure.setPerformed(dvDateTimeToFhir(action.getTime()));
        }

        // Map description data
        if (action.getDescription() != null) {
            List<Element> elements = extractElements(action.getDescription());
            for (Element element : elements) {
                mapActionElement(element, procedure);
            }
        }

        return List.of(procedure);
    }

    private Procedure.ProcedureStatus mapIsmTransitionToStatus(Action action) {
        if (action.getIsmTransition() == null || action.getIsmTransition().getCurrentState() == null) {
            return Procedure.ProcedureStatus.UNKNOWN;
        }

        DvCodedText currentState = action.getIsmTransition().getCurrentState();
        String code = currentState.getDefiningCode() != null
                ? currentState.getDefiningCode().getCodeString()
                : "";

        // openEHR ISM states mapped to FHIR Procedure status
        // See: https://specifications.openehr.org/releases/RM/latest/ehr.html#_ism_transition_class
        return switch (code) {
            case "524" -> Procedure.ProcedureStatus.PREPARATION; // initial
            case "526", "527", "528" -> Procedure.ProcedureStatus.INPROGRESS; // planned/scheduled/active
            case "529" -> Procedure.ProcedureStatus.ONHOLD; // suspended
            case "530" -> Procedure.ProcedureStatus.STOPPED; // aborted
            case "531" -> Procedure.ProcedureStatus.ENTEREDINERROR; // cancelled
            case "532", "533" -> Procedure.ProcedureStatus.COMPLETED; // completed/expired
            default -> Procedure.ProcedureStatus.UNKNOWN;
        };
    }

    private void mapActionElement(Element element, Procedure procedure) {
        String nameValue = element.getName() != null ? element.getName().getValue() : "";
        String nameLower = nameValue.toLowerCase();

        if (nameLower.contains("procedure") || nameLower.contains("intervention")) {
            if (element.getValue() instanceof DvCodedText dvCodedText) {
                procedure.setCode(dvTextToCodeableConcept(dvCodedText));
            } else if (element.getValue() instanceof DvText dvText) {
                procedure.setCode(dvTextToCodeableConcept(dvText));
            }
        } else if (nameLower.contains("body site") || nameLower.contains("location")) {
            if (element.getValue() instanceof DvCodedText dvCodedText) {
                procedure.addBodySite(dvTextToCodeableConcept(dvCodedText));
            } else if (element.getValue() instanceof DvText dvText) {
                procedure.addBodySite(dvTextToCodeableConcept(dvText));
            }
        } else if (nameLower.contains("outcome") || nameLower.contains("result")) {
            if (element.getValue() instanceof DvCodedText dvCodedText) {
                procedure.setOutcome(dvTextToCodeableConcept(dvCodedText));
            } else if (element.getValue() instanceof DvText dvText) {
                procedure.setOutcome(dvTextToCodeableConcept(dvText));
            }
        } else if (nameLower.contains("date") || nameLower.contains("time")) {
            if (element.getValue() instanceof DvDateTime dvDateTime) {
                procedure.setPerformed(dvDateTimeToFhir(dvDateTime));
            }
        } else if (nameLower.contains("comment") || nameLower.contains("note")) {
            if (element.getValue() instanceof DvText dvText) {
                procedure.addNote().setText(dvText.getValue());
            }
        }
    }
}
