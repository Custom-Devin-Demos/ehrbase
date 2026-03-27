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
import com.nedap.archie.rm.composition.Section;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Handles openEHR {@link Section} content items by recursively delegating to other
 * {@link ContentItemFhirMapper} implementations for the nested content items.
 */
@Component
public class SectionFhirMapper implements ContentItemFhirMapper {

    private final List<ContentItemFhirMapper> entryMappers;

    public SectionFhirMapper(
            ObservationFhirMapper observationMapper,
            EvaluationFhirMapper evaluationMapper,
            InstructionFhirMapper instructionMapper,
            ActionFhirMapper actionMapper) {
        this.entryMappers = List.of(observationMapper, evaluationMapper, instructionMapper, actionMapper);
    }

    @Override
    public boolean supports(ContentItem item) {
        return item instanceof Section;
    }

    @Override
    public List<Resource> map(ContentItem item, String ehrId) {
        if (!(item instanceof Section section)) {
            return Collections.emptyList();
        }

        List<Resource> resources = new ArrayList<>();

        if (section.getItems() != null) {
            for (ContentItem child : section.getItems()) {
                if (child instanceof Section) {
                    // Recursively process nested sections
                    resources.addAll(map(child, ehrId));
                } else {
                    // Delegate to entry-level mappers
                    for (ContentItemFhirMapper mapper : entryMappers) {
                        if (mapper.supports(child)) {
                            resources.addAll(mapper.map(child, ehrId));
                            break;
                        }
                    }
                }
            }
        }

        return resources;
    }
}
