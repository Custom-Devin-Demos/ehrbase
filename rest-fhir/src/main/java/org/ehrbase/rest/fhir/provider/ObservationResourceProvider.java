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
package org.ehrbase.rest.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;

/**
 * Stub FHIR Resource Provider for Observation. All operations return 501 Not Implemented.
 */
public class ObservationResourceProvider implements IResourceProvider {

    private static final int SC_NOT_IMPLEMENTED = 501;
    private static final String NOT_YET_IMPLEMENTED = "Observation resource provider is not yet implemented";

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Observation.class;
    }

    @Read
    public Observation read(@IdParam IdType theId) {
        throw new BaseServerResponseException(SC_NOT_IMPLEMENTED, NOT_YET_IMPLEMENTED) {};
    }

    @Search
    public List<Observation> search() {
        throw new BaseServerResponseException(SC_NOT_IMPLEMENTED, NOT_YET_IMPLEMENTED) {};
    }
}
