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

import java.util.Set;

/**
 * Utility for recognizing terminology URLs and openEHR {@code service-api} identifiers that belong
 * to the Public Health Information Repository (PHIR).
 *
 * <p>The PHIR integration mirrors the shape of {@link FhirTerminologyValidation}'s internal
 * {@code isValidTerminology(Optional&lt;String&gt;)} check: a PHIR terminology reference is
 * considered valid when its {@code service-api} (or system URI) {@code startsWith} one of a known
 * set of PHIR authorities / schemes.
 *
 * <h2>Assumed PHIR authority patterns</h2>
 *
 * The authority list below is a best-effort scaffold based on publicly-known PHIR naming
 * conventions. Entries are intentionally conservative and should be tightened once the final PHIR
 * specification is published.
 *
 * <ul>
 *   <li>{@code phir://} — custom PHIR URI scheme for terminology service-api identifiers.
 *   <li>{@code //phir.cdc.gov} — protocol-relative authority for the CDC PHIR endpoint.
 *   <li>{@code terminology://phir.cdc.gov} — openEHR-style terminology service-api identifier
 *       pointing at the CDC PHIR authority.
 *   <li>{@code https://phir.cdc.gov/} / {@code http://phir.cdc.gov/} — absolute HTTP(S) URLs
 *       rooted at the CDC PHIR authority.
 * </ul>
 *
 * <p>Step 1 of the PHIR integration (the {@code PhirTerminologyValidation} class) is expected to
 * delegate its {@code supports(...)} / {@code validate(...)} service-api checks to
 * {@link #isPhirTerminology(String)} and, where appropriate, to consult
 * {@link #ACCEPTED_PHIR_SERVICE_APIS} directly.
 *
 * <p>This class is intentionally a static-only utility; it holds no state and performs no network
 * I/O.
 */
// TODO(PHIR spec): confirm final authority list once the official PHIR specification is published.
public final class PhirTerminologyUrlValidator {

    /**
     * Publicly-known PHIR {@code service-api} identifier prefixes.
     *
     * <p>Each entry is matched against candidate URIs using a {@code startsWith} comparison — the
     * same strategy used by {@link FhirTerminologyValidation} for its FHIR authority list. Entries
     * are lower-cased and include both the scheme-full form (e.g. {@code phir://}) and the
     * protocol-relative / terminology-scheme forms (e.g. {@code //phir.cdc.gov},
     * {@code terminology://phir.cdc.gov}) so callers do not have to normalize input before calling
     * {@link #isPhirTerminology(String)}.
     */
    // TODO(PHIR spec): replace guessed entries with the canonical authority list once finalized.
    public static final Set<String> ACCEPTED_PHIR_SERVICE_APIS = Set.of(
            // Custom PHIR scheme — mirrors openEHR's use of a scheme-only prefix for
            // service-api identifiers.
            "phir://",
            // CDC-hosted PHIR authority — the most widely-referenced PHIR endpoint in public
            // materials.
            "//phir.cdc.gov",
            "terminology://phir.cdc.gov",
            "https://phir.cdc.gov/",
            "http://phir.cdc.gov/");

    private PhirTerminologyUrlValidator() {
        // utility class — not instantiable
    }

    /**
     * Returns {@code true} if {@code systemUri} references a recognized PHIR authority.
     *
     * <p>The check is a case-insensitive {@code startsWith} comparison against
     * {@link #ACCEPTED_PHIR_SERVICE_APIS}. {@code null} and blank input return {@code false}.
     *
     * @param systemUri a candidate terminology system URI or {@code service-api} identifier
     * @return {@code true} if the URI is rooted at a recognized PHIR authority
     */
    public static boolean isPhirTerminology(String systemUri) {
        if (systemUri == null || systemUri.isBlank()) {
            return false;
        }
        String normalized = systemUri.toLowerCase();
        return ACCEPTED_PHIR_SERVICE_APIS.stream().anyMatch(api -> normalized.startsWith(api.toLowerCase()));
    }
}
