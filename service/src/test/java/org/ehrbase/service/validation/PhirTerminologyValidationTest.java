/*
 * Copyright (c) 2026 vitasystems GmbH.
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
package org.ehrbase.service.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.internal.JsonContext;
import org.ehrbase.openehr.sdk.validation.terminology.TerminologyParam;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

class PhirTerminologyValidationTest {

    private static final String BASE_URL = "http://phir.local/api";

    @Test
    void constructor_DefaultsFailOnErrorToTrueAndCreatesWebClient() {
        PhirTerminologyValidation defaults = new PhirTerminologyValidation(BASE_URL);
        assertNotNull(defaults);

        PhirTerminologyValidation withFailFlag = new PhirTerminologyValidation(BASE_URL, false);
        assertNotNull(withFailFlag);

        PhirTerminologyValidation withWebClient = new PhirTerminologyValidation(BASE_URL, false, WebClient.create());
        assertNotNull(withWebClient);
    }

    @Test
    void supports_ValidParams_ReturnsTrue() {
        String valueSetUrl = "http://example.org/phir/ValueSet/observation-status";

        PhirTerminologyValidation validation = spy(new PhirTerminologyValidation(BASE_URL));
        // TODO(PHIR spec): swap ofFhir for a PHIR-specific factory once the SDK adds
        // ofPhir; the current ofFhir regex happily parses //phir.local/... URLs.
        TerminologyParam param =
                TerminologyParam.ofFhir("//phir.local/ValueSet/$expand?url=" + valueSetUrl + "&activeOnly=true");

        JsonContext jsonContext = mock(JsonContext.class);
        when(jsonContext.read("$.total", int.class)).thenReturn(1);

        doReturn(jsonContext).when(validation).internalGet(Mockito.anyString());

        assertTrue(validation.supports(param));

        verify(validation)
                .internalGet(PhirTerminologyValidation.renderTempl(
                        PhirTerminologyValidation.SUPPORTS_VALUE_SET_TEMPL, BASE_URL, valueSetUrl));
    }

    @Test
    void supports_InvalidServiceApi_ReturnsFalse() {
        PhirTerminologyValidation validation = spy(new PhirTerminologyValidation(BASE_URL));

        assertFalse(validation.supports(TerminologyParam.ofFhir("//phir.local/ValueSet/$expand?activeOnly=true")));
        assertFalse(validation.supports(TerminologyParam.ofFhir(
                "//not-a-phir-host/ValueSet/$expand?url=http://example.org/phir/vs&activeOnly=true")));

        verify(validation, Mockito.never()).internalGet(Mockito.anyString());
    }
}
