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
import com.nedap.archie.rm.composition.Composition;
import org.ehrbase.api.exception.InternalServerException;
import org.ehrbase.api.service.FhirCompositionService;
import org.ehrbase.api.util.LocatableUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link FhirCompositionService} implementation that uses {@link OpenEhrToFhirMapper} to transform
 * openEHR compositions into FHIR R4 Bundles and validates the output against FHIR R4 profiles.
 */
@Service
public class FhirCompositionServiceImp implements FhirCompositionService {

    private static final Logger LOG = LoggerFactory.getLogger(FhirCompositionServiceImp.class);

    private final FhirContext fhirContext;
    private final OpenEhrToFhirMapper mapper;
    private final FhirBundleValidator validator;
    private final boolean validationEnabled;

    public FhirCompositionServiceImp() {
        this(FhirContext.forR4(), new FhirMappingConfig(), false);
    }

    /**
     * Constructor for dependency injection with custom configuration.
     *
     * @param fhirContext    the FHIR context
     * @param mappingConfig  the mapping configuration
     * @param enableValidation whether to run FHIR validation on every serialize() call
     */
    public FhirCompositionServiceImp(FhirContext fhirContext, FhirMappingConfig mappingConfig,
                                     boolean enableValidation) {
        this.fhirContext = fhirContext;
        this.mapper = new OpenEhrToFhirMapper(mappingConfig);
        this.validator = new FhirBundleValidator(fhirContext);
        this.validationEnabled = enableValidation;
    }

    @Override
    public String serialize(Composition composition) {
        String templateId = LocatableUtils.getTemplateId(composition);
        return serialize(composition, templateId);
    }

    @Override
    public String serialize(Composition composition, String templateId) {
        LOG.debug("Serializing composition to FHIR R4 Bundle [templateId={}]", templateId);

        try {
            Bundle bundle = mapper.map(composition, templateId);

            // Validate the produced bundle only when validation is enabled
            if (validationEnabled) {
                FhirBundleValidator.FhirValidationOutcome outcome = validator.validate(bundle);
                if (!outcome.valid()) {
                    LOG.warn(
                            "Produced FHIR Bundle has validation issues: {}. Returning bundle anyway.",
                            outcome.errorSummary());
                }
            }

            return fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
        } catch (Exception e) {
            throw new InternalServerException("Failed to serialize composition to FHIR R4", e);
        }
    }

    /**
     * Returns the underlying mapper for advanced use cases (e.g. getting the raw Bundle object).
     */
    public OpenEhrToFhirMapper getMapper() {
        return mapper;
    }

    /**
     * Returns the FHIR context used by this service.
     */
    public FhirContext getFhirContext() {
        return fhirContext;
    }
}
