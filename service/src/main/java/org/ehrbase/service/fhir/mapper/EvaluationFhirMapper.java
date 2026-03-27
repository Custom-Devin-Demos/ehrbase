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

import static org.ehrbase.service.fhir.mapper.ObservationFhirMapper.dvTextToCodeableConcept;
import static org.ehrbase.service.fhir.mapper.ObservationFhirMapper.extractElements;

import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import java.util.Collections;
import java.util.List;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Maps openEHR {@link Evaluation} entries to FHIR R4 {@link Condition} resources.
 * Evaluations in openEHR typically represent clinical assessments, diagnoses,
 * adverse reactions, and other evaluated clinical statements.
 */
@Component
public class EvaluationFhirMapper implements ContentItemFhirMapper {

    @Override
    public boolean supports(ContentItem item) {
        return item instanceof Evaluation;
    }

    @Override
    public List<Resource> map(ContentItem item, String ehrId) {
        if (!(item instanceof Evaluation evaluation)) {
            return Collections.emptyList();
        }

        Condition condition = new Condition();
        condition.setSubject(new Reference("Patient/" + ehrId));
        condition.setClinicalStatus(new org.hl7.fhir.r4.model.CodeableConcept()
                .addCoding(new org.hl7.fhir.r4.model.Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical")
                        .setCode("active")));

        // Map the evaluation name as the condition code
        DvText name = evaluation.getName();
        condition.setCode(dvTextToCodeableConcept(name));

        // Map data elements
        if (evaluation.getData() != null) {
            List<Element> elements = extractElements(evaluation.getData());
            for (Element element : elements) {
                mapEvaluationElement(element, condition);
            }
        }

        return List.of(condition);
    }

    private void mapEvaluationElement(Element element, Condition condition) {
        String nameValue = element.getName() != null ? element.getName().getValue() : "";
        String nameLower = nameValue.toLowerCase();

        if (nameLower.contains("diagnosis") || nameLower.contains("problem")) {
            if (element.getValue() instanceof DvCodedText dvCodedText) {
                condition.setCode(dvTextToCodeableConcept(dvCodedText));
            } else if (element.getValue() instanceof DvText dvText) {
                condition.setCode(dvTextToCodeableConcept(dvText));
            }
        } else if (nameLower.contains("onset") || nameLower.contains("date")) {
            if (element.getValue() instanceof DvDateTime dvDateTime && dvDateTime.getValue() != null) {
                condition.setOnset(new DateTimeType(dvDateTime.getValue().toString()));
            }
        } else if (nameLower.contains("severity")) {
            if (element.getValue() instanceof DvCodedText dvCodedText) {
                condition.setSeverity(dvTextToCodeableConcept(dvCodedText));
            } else if (element.getValue() instanceof DvText dvText) {
                condition.setSeverity(dvTextToCodeableConcept(dvText));
            }
        } else if (nameLower.contains("body site") || nameLower.contains("location")) {
            if (element.getValue() instanceof DvCodedText dvCodedText) {
                condition.addBodySite(dvTextToCodeableConcept(dvCodedText));
            } else if (element.getValue() instanceof DvText dvText) {
                condition.addBodySite(dvTextToCodeableConcept(dvText));
            }
        } else if (nameLower.contains("comment") || nameLower.contains("note")) {
            if (element.getValue() instanceof DvText dvText) {
                condition.addNote().setText(dvText.getValue());
            }
        }
    }
}
