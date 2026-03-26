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
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import com.nedap.archie.rm.composition.Composition;
import java.util.List;
import org.ehrbase.api.exception.InternalServerException;
import org.ehrbase.api.service.fhir.FhirCompositionService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link FhirCompositionService} implementation that uses {@link OpenEhrFhirMapper} to convert
 * openEHR Compositions to FHIR R4 Bundles and provides FHIR R4 validation.
 *
 * <p>This service is a Spring-managed bean that is auto-discovered via component scanning. It
 * maintains a shared {@link FhirContext} (R4) for JSON serialization and validation.
 */
@Service
public class FhirCompositionServiceImp implements FhirCompositionService {

    private static final Logger LOG = LoggerFactory.getLogger(FhirCompositionServiceImp.class);

    private final FhirContext fhirContext;
    private final OpenEhrFhirMapper mapper;

    public FhirCompositionServiceImp() {
        this.fhirContext = FhirContext.forR4();
        this.mapper = new OpenEhrFhirMapper();
    }

    @Override
    public Bundle toFhirBundle(Composition composition) {
        try {
            return mapper.mapCompositionToBundle(composition);
        } catch (Exception e) {
            LOG.error("Failed to transform openEHR Composition to FHIR R4 Bundle", e);
            throw new InternalServerException("FHIR transformation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String serializeToFhirJson(Composition composition) {
        Bundle bundle = toFhirBundle(composition);
        return serializeBundleToJson(bundle);
    }

    @Override
    public String serializeBundleToJson(Bundle bundle) {
        IParser jsonParser = fhirContext.newJsonParser().setPrettyPrint(true);
        return jsonParser.encodeResourceToString(bundle);
    }

    @Override
    public OperationOutcome validateBundle(Bundle bundle) {
        FhirValidator validator = fhirContext.newValidator();
        ValidationResult result = validator.validateWithResult(bundle);

        OperationOutcome outcome = new OperationOutcome();

        List<SingleValidationMessage> messages = result.getMessages();
        for (SingleValidationMessage message : messages) {
            OperationOutcome.IssueSeverity severity =
                    switch (message.getSeverity()) {
                        case ERROR -> OperationOutcome.IssueSeverity.ERROR;
                        case WARNING -> OperationOutcome.IssueSeverity.WARNING;
                        case INFORMATION -> OperationOutcome.IssueSeverity.INFORMATION;
                        default -> OperationOutcome.IssueSeverity.INFORMATION;
                    };

            outcome.addIssue()
                    .setSeverity(severity)
                    .setCode(OperationOutcome.IssueType.PROCESSING)
                    .setDiagnostics(message.getMessage())
                    .setLocation(List.of(new org.hl7.fhir.r4.model.StringType(message.getLocationString())));
        }

        if (messages.isEmpty()) {
            outcome.addIssue()
                    .setSeverity(OperationOutcome.IssueSeverity.INFORMATION)
                    .setCode(OperationOutcome.IssueType.INFORMATIONAL)
                    .setDiagnostics("Validation passed with no issues");
        }

        return outcome;
    }

    /**
     * Returns the shared FHIR context. Exposed for use by REST controllers that need to serialize
     * FHIR resources.
     */
    public FhirContext getFhirContext() {
        return fhirContext;
    }
}
