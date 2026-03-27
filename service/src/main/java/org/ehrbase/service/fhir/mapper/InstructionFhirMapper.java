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

import com.nedap.archie.rm.composition.Activity;
import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.composition.Instruction;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Maps openEHR {@link Instruction} entries to FHIR R4 {@link MedicationStatement} resources.
 * Instructions in openEHR typically represent medication orders, therapeutic instructions,
 * and other planned clinical activities.
 */
@Component
public class InstructionFhirMapper implements ContentItemFhirMapper {

    @Override
    public boolean supports(ContentItem item) {
        return item instanceof Instruction;
    }

    @Override
    public List<Resource> map(ContentItem item, String ehrId) {
        if (!(item instanceof Instruction instruction)) {
            return Collections.emptyList();
        }

        List<Resource> resources = new ArrayList<>();

        // Each activity in the instruction becomes a MedicationStatement
        if (instruction.getActivities() != null) {
            for (Activity activity : instruction.getActivities()) {
                MedicationStatement medStatement = mapActivity(activity, instruction, ehrId);
                resources.add(medStatement);
            }
        }

        // If no activities, create a single MedicationStatement from the instruction name
        if (resources.isEmpty()) {
            MedicationStatement medStatement = new MedicationStatement();
            medStatement.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
            medStatement.setSubject(new Reference("Patient/" + ehrId));
            medStatement.setMedication(dvTextToCodeableConcept(instruction.getName()));

            if (instruction.getNarrative() != null) {
                medStatement.addNote().setText(instruction.getNarrative().getValue());
            }

            resources.add(medStatement);
        }

        return resources;
    }

    private MedicationStatement mapActivity(Activity activity, Instruction instruction, String ehrId) {
        MedicationStatement medStatement = new MedicationStatement();
        medStatement.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
        medStatement.setSubject(new Reference("Patient/" + ehrId));

        // Use instruction name as medication code
        medStatement.setMedication(dvTextToCodeableConcept(instruction.getName()));

        // Map activity description data
        if (activity.getDescription() != null) {
            List<Element> elements = extractElements(activity.getDescription());
            Dosage dosage = new Dosage();
            boolean hasDosage = false;

            for (Element element : elements) {
                String nameValue = element.getName() != null ? element.getName().getValue() : "";
                String nameLower = nameValue.toLowerCase();

                if (nameLower.contains("medication") || nameLower.contains("medicine")) {
                    if (element.getValue() instanceof DvCodedText dvCodedText) {
                        medStatement.setMedication(dvTextToCodeableConcept(dvCodedText));
                    } else if (element.getValue() instanceof DvText dvText) {
                        medStatement.setMedication(dvTextToCodeableConcept(dvText));
                    }
                } else if (nameLower.contains("dose") || nameLower.contains("amount")) {
                    if (element.getValue() instanceof DvQuantity dvQuantity) {
                        Dosage.DosageDoseAndRateComponent doseAndRate = new Dosage.DosageDoseAndRateComponent();
                        Quantity qty = new Quantity();
                        qty.setValue(dvQuantity.getMagnitude());
                        qty.setUnit(dvQuantity.getUnits());
                        doseAndRate.setDose(qty);
                        dosage.addDoseAndRate(doseAndRate);
                        hasDosage = true;
                    }
                } else if (nameLower.contains("route")) {
                    if (element.getValue() instanceof DvCodedText dvCodedText) {
                        dosage.setRoute(dvTextToCodeableConcept(dvCodedText));
                        hasDosage = true;
                    } else if (element.getValue() instanceof DvText dvText) {
                        dosage.setRoute(dvTextToCodeableConcept(dvText));
                        hasDosage = true;
                    }
                } else if (nameLower.contains("frequency") || nameLower.contains("timing")) {
                    if (element.getValue() instanceof DvText dvText) {
                        dosage.setText(dvText.getValue());
                        hasDosage = true;
                    }
                } else if (nameLower.contains("date") || nameLower.contains("start")) {
                    if (element.getValue() instanceof DvDateTime dvDateTime && dvDateTime.getValue() != null) {
                        medStatement.setEffective(
                                new DateTimeType(dvDateTime.getValue().toString()));
                    }
                } else if (nameLower.contains("comment") || nameLower.contains("note")) {
                    if (element.getValue() instanceof DvText dvText) {
                        medStatement.addNote().setText(dvText.getValue());
                    }
                }
            }

            if (hasDosage) {
                medStatement.addDosage(dosage);
            }
        }

        // Map instruction narrative
        if (instruction.getNarrative() != null) {
            medStatement.addNote().setText(instruction.getNarrative().getValue());
        }

        return medStatement;
    }
}
