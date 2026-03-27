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

import com.nedap.archie.rm.composition.ContentItem;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.Item;
import com.nedap.archie.rm.datastructures.ItemList;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Maps openEHR {@link Observation} entries to FHIR R4 {@link org.hl7.fhir.r4.model.Observation}
 * resources. Handles lab results, vital signs, and other observational clinical data.
 */
@Component
public class ObservationFhirMapper implements ContentItemFhirMapper {

    @Override
    public boolean supports(ContentItem item) {
        return item instanceof Observation;
    }

    @Override
    public List<Resource> map(ContentItem item, String ehrId) {
        if (!(item instanceof Observation observation)) {
            return Collections.emptyList();
        }

        org.hl7.fhir.r4.model.Observation fhirObs = new org.hl7.fhir.r4.model.Observation();
        fhirObs.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
        fhirObs.setSubject(new Reference("Patient/" + ehrId));

        // Map the observation name to FHIR code
        DvText name = observation.getName();
        fhirObs.setCode(dvTextToCodeableConcept(name));

        // Map observation time from the data events
        if (observation.getData() != null
                && observation.getData().getEvents() != null
                && !observation.getData().getEvents().isEmpty()) {
            var event = observation.getData().getEvents().get(0);
            if (event.getTime() != null) {
                fhirObs.setEffective(dvDateTimeToFhir(event.getTime()));
            }
            // Map observation values from event data
            if (event.getData() != null) {
                mapDataItems(event.getData(), fhirObs);
            }
        }

        // Map protocol items (e.g., method, device)
        if (observation.getProtocol() != null) {
            mapProtocol(observation.getProtocol(), fhirObs);
        }

        return List.of(fhirObs);
    }

    private void mapDataItems(ItemStructure data, org.hl7.fhir.r4.model.Observation fhirObs) {
        List<Element> elements = extractElements(data);
        if (elements.isEmpty()) {
            return;
        }

        if (elements.size() == 1) {
            // Single value observation
            mapElementValue(elements.get(0), fhirObs);
        } else {
            // Multi-component observation
            for (Element element : elements) {
                org.hl7.fhir.r4.model.Observation.ObservationComponentComponent component =
                        new org.hl7.fhir.r4.model.Observation.ObservationComponentComponent();
                component.setCode(dvTextToCodeableConcept(element.getName()));
                if (element.getValue() instanceof DvQuantity dvQuantity) {
                    Quantity qty = new Quantity();
                    qty.setValue(dvQuantity.getMagnitude());
                    qty.setUnit(dvQuantity.getUnits());
                    component.setValue(qty);
                } else if (element.getValue() instanceof DvCodedText dvCodedText) {
                    component.setValue(dvTextToCodeableConcept(dvCodedText));
                } else if (element.getValue() instanceof DvText dvText) {
                    component.setValue(dvTextToCodeableConcept(dvText));
                }
                fhirObs.addComponent(component);
            }
        }
    }

    private void mapElementValue(Element element, org.hl7.fhir.r4.model.Observation fhirObs) {
        if (element.getValue() instanceof DvQuantity dvQuantity) {
            Quantity qty = new Quantity();
            qty.setValue(dvQuantity.getMagnitude());
            qty.setUnit(dvQuantity.getUnits());
            fhirObs.setValue(qty);
        } else if (element.getValue() instanceof DvCodedText dvCodedText) {
            fhirObs.setValue(dvTextToCodeableConcept(dvCodedText));
        } else if (element.getValue() instanceof DvText dvText) {
            fhirObs.setValue(dvTextToCodeableConcept(dvText));
        } else if (element.getValue() instanceof DvDateTime dvDateTime) {
            fhirObs.setValue(dvDateTimeToFhir(dvDateTime));
        }
    }

    private void mapProtocol(ItemStructure protocol, org.hl7.fhir.r4.model.Observation fhirObs) {
        List<Element> elements = extractElements(protocol);
        for (Element element : elements) {
            String nameValue = element.getName() != null ? element.getName().getValue() : "";
            if (nameValue.toLowerCase().contains("method") && element.getValue() instanceof DvText dvText) {
                fhirObs.setMethod(dvTextToCodeableConcept(dvText));
            }
        }
    }

    static CodeableConcept dvTextToCodeableConcept(DvText dvText) {
        CodeableConcept cc = new CodeableConcept();
        if (dvText instanceof DvCodedText coded && coded.getDefiningCode() != null) {
            Coding coding = new Coding();
            if (coded.getDefiningCode().getTerminologyId() != null) {
                coding.setSystem(coded.getDefiningCode().getTerminologyId().getValue());
            }
            coding.setCode(coded.getDefiningCode().getCodeString());
            coding.setDisplay(coded.getValue());
            cc.addCoding(coding);
        }
        cc.setText(dvText.getValue());
        return cc;
    }

    static DateTimeType dvDateTimeToFhir(DvDateTime dvDateTime) {
        if (dvDateTime.getValue() != null) {
            return new DateTimeType(dvDateTime.getValue().toString());
        }
        return new DateTimeType();
    }

    static List<Element> extractElements(ItemStructure structure) {
        List<Element> elements = new ArrayList<>();
        if (structure instanceof ItemTree itemTree && itemTree.getItems() != null) {
            for (Item item : itemTree.getItems()) {
                if (item instanceof Element element) {
                    elements.add(element);
                }
            }
        } else if (structure instanceof ItemList itemList && itemList.getItems() != null) {
            for (Item item : itemList.getItems()) {
                if (item instanceof Element element) {
                    elements.add(element);
                }
            }
        }
        return elements;
    }
}
