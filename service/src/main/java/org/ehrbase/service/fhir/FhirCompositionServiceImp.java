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
import com.nedap.archie.rm.composition.Composition;
import java.util.UUID;
import org.ehrbase.api.service.fhir.FhirCompositionService;
import org.ehrbase.service.fhir.mapper.OpenEhrToFhirBundleMapper;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link FhirCompositionService} implementation that converts openEHR Composition RM objects
 * into FHIR R4 Bundle JSON using the HAPI FHIR library.
 *
 * <p>The service leverages {@link OpenEhrToFhirBundleMapper} for the structural mapping and
 * the HAPI {@link FhirContext} for JSON serialization.
 */
@Service
public class FhirCompositionServiceImp implements FhirCompositionService {

    private static final Logger LOG = LoggerFactory.getLogger(FhirCompositionServiceImp.class);

    private final OpenEhrToFhirBundleMapper bundleMapper;
    private final FhirContext fhirContext;

    public FhirCompositionServiceImp(OpenEhrToFhirBundleMapper bundleMapper) {
        this.bundleMapper = bundleMapper;
        this.fhirContext = FhirContext.forR4();
    }

    @Override
    public String toFhirBundle(UUID ehrId, Composition composition) {
        LOG.debug("Converting openEHR composition to FHIR R4 Bundle for EHR: {}", ehrId);

        Bundle bundle = bundleMapper.map(ehrId, composition);

        IParser jsonParser = fhirContext.newJsonParser();
        jsonParser.setPrettyPrint(true);

        String result = jsonParser.encodeResourceToString(bundle);

        LOG.debug(
                "Successfully converted composition to FHIR Bundle with {} entries",
                bundle.getEntry().size());

        return result;
    }
}
