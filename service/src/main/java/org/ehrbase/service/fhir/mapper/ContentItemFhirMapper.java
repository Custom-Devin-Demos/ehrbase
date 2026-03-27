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
import java.util.List;
import org.hl7.fhir.r4.model.Resource;

/**
 * Strategy interface for mapping an openEHR {@link ContentItem} (Section, Entry, etc.)
 * to zero or more FHIR R4 {@link Resource} instances.
 */
public interface ContentItemFhirMapper {

    /**
     * Returns {@code true} if this mapper can handle the given content item.
     */
    boolean supports(ContentItem item);

    /**
     * Maps the given content item to FHIR resources.
     *
     * @param item    the openEHR content item
     * @param ehrId   the EHR identifier (used as FHIR Patient logical reference)
     * @return list of mapped FHIR resources (may be empty, never {@code null})
     */
    List<Resource> map(ContentItem item, String ehrId);
}
