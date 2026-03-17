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
package org.ehrbase.rest.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.RestfulServer;
import java.util.List;
import org.ehrbase.rest.fhir.provider.BundleResourceProvider;
import org.ehrbase.rest.fhir.provider.ConditionResourceProvider;
import org.ehrbase.rest.fhir.provider.EhrbaseCapabilityStatementProvider;
import org.ehrbase.rest.fhir.provider.ObservationResourceProvider;
import org.ehrbase.rest.fhir.provider.PatientResourceProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "fhir.facade", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RestFhirModuleConfiguration {

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();
    }

    @Bean
    public PatientResourceProvider patientResourceProvider() {
        return new PatientResourceProvider();
    }

    @Bean
    public ObservationResourceProvider observationResourceProvider() {
        return new ObservationResourceProvider();
    }

    @Bean
    public ConditionResourceProvider conditionResourceProvider() {
        return new ConditionResourceProvider();
    }

    @Bean
    public BundleResourceProvider bundleResourceProvider() {
        return new BundleResourceProvider();
    }

    @Bean
    public EhrbaseCapabilityStatementProvider ehrbaseCapabilityStatementProvider() {
        return new EhrbaseCapabilityStatementProvider();
    }

    @Bean
    public ServletRegistrationBean<RestfulServer> fhirServletRegistration(
            FhirContext fhirContext,
            EhrbaseCapabilityStatementProvider capabilityStatementProvider,
            PatientResourceProvider patientResourceProvider,
            ObservationResourceProvider observationResourceProvider,
            ConditionResourceProvider conditionResourceProvider,
            BundleResourceProvider bundleResourceProvider) {

        RestfulServer server = new RestfulServer(fhirContext);
        server.setServerConformanceProvider(capabilityStatementProvider);
        server.registerProviders(List.of(
                patientResourceProvider, observationResourceProvider, conditionResourceProvider,
                bundleResourceProvider));

        ServletRegistrationBean<RestfulServer> registration = new ServletRegistrationBean<>(server, "/fhir/*");
        registration.setName("fhirServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
