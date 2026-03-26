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
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import java.util.List;
import java.util.stream.Collectors;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates FHIR R4 {@link Bundle} resources using the HAPI FHIR validator with R4 profile
 * support.
 *
 * <p>The validator uses an in-memory terminology server and the default R4 profiles to validate
 * produced Bundles without requiring an external FHIR server.
 */
public class FhirBundleValidator {

    private static final Logger LOG = LoggerFactory.getLogger(FhirBundleValidator.class);

    private final FhirContext fhirContext;
    private final FhirValidator validator;

    public FhirBundleValidator(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
        this.validator = createValidator(fhirContext);
    }

    private static FhirValidator createValidator(FhirContext ctx) {
        ValidationSupportChain supportChain = new ValidationSupportChain(
                new DefaultProfileValidationSupport(ctx),
                new InMemoryTerminologyServerValidationSupport(ctx),
                new CommonCodeSystemsTerminologyService(ctx));

        FhirInstanceValidator instanceValidator = new FhirInstanceValidator(supportChain);
        // Only error on structural issues; informational messages about unknown code systems are OK
        instanceValidator.setNoTerminologyChecks(false);

        FhirValidator validator = ctx.newValidator();
        validator.registerValidatorModule(instanceValidator);
        return validator;
    }

    /**
     * Validates the given FHIR R4 Bundle.
     *
     * @param bundle the bundle to validate
     * @return the validation result
     */
    public FhirValidationOutcome validate(Bundle bundle) {
        String json = fhirContext.newJsonParser().setPrettyPrint(false).encodeResourceToString(bundle);
        ValidationResult result = validator.validateWithResult(json);

        List<SingleValidationMessage> errors = result.getMessages().stream()
                .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR
                        || m.getSeverity() == ResultSeverityEnum.FATAL)
                .collect(Collectors.toList());

        List<SingleValidationMessage> warnings = result.getMessages().stream()
                .filter(m -> m.getSeverity() == ResultSeverityEnum.WARNING)
                .collect(Collectors.toList());

        if (!errors.isEmpty()) {
            LOG.warn(
                    "FHIR validation produced {} error(s): {}",
                    errors.size(),
                    errors.stream().map(SingleValidationMessage::getMessage).collect(Collectors.joining("; ")));
        }

        return new FhirValidationOutcome(result.isSuccessful(), errors, warnings);
    }

    /**
     * Outcome of a FHIR validation run.
     */
    public record FhirValidationOutcome(
            boolean valid,
            List<SingleValidationMessage> errors,
            List<SingleValidationMessage> warnings) {

        public String errorSummary() {
            if (errors.isEmpty()) {
                return "No errors";
            }
            return errors.stream().map(SingleValidationMessage::getMessage).collect(Collectors.joining("; "));
        }
    }
}
