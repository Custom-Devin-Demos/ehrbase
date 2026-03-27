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

import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DataValue;
import com.nedap.archie.rm.datavalues.DvBoolean;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvIdentifier;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.DvURI;
import com.nedap.archie.rm.datavalues.encapsulated.DvParsable;
import com.nedap.archie.rm.datavalues.quantity.DvCount;
import com.nedap.archie.rm.datavalues.quantity.DvOrdinal;
import com.nedap.archie.rm.datavalues.quantity.DvProportion;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDate;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDuration;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvTime;
import com.nedap.archie.rm.generic.PartyIdentified;
import com.nedap.archie.rm.generic.PartyProxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Ratio;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.TimeType;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.r4.model.UriType;

/**
 * Maps openEHR RM data types (from the Archie library) to FHIR R4 data types.
 *
 * <p>This utility class provides static conversion methods for transforming individual openEHR
 * Reference Model data values into their closest FHIR R4 equivalents. The mappings follow the
 * general guidance from the openEHR-FHIR interoperability specifications.
 */
public final class OpenEhrFhirDataTypeMapper {

    private OpenEhrFhirDataTypeMapper() {
        // utility class
    }

    /**
     * Maps an openEHR {@link DataValue} to the closest FHIR R4 {@link Type}.
     *
     * @param dataValue the openEHR data value to convert
     * @return the corresponding FHIR R4 type, or a {@link StringType} fallback
     */
    public static Type mapDataValue(DataValue dataValue) {
        if (dataValue == null) {
            return null;
        }

        if (dataValue instanceof DvCodedText dvCodedText) {
            return mapDvCodedText(dvCodedText);
        } else if (dataValue instanceof DvText dvText) {
            return mapDvText(dvText);
        } else if (dataValue instanceof DvQuantity dvQuantity) {
            return mapDvQuantity(dvQuantity);
        } else if (dataValue instanceof DvDateTime dvDateTime) {
            return mapDvDateTime(dvDateTime);
        } else if (dataValue instanceof DvDate dvDate) {
            return mapDvDate(dvDate);
        } else if (dataValue instanceof DvTime dvTime) {
            return mapDvTime(dvTime);
        } else if (dataValue instanceof DvBoolean dvBoolean) {
            return mapDvBoolean(dvBoolean);
        } else if (dataValue instanceof DvCount dvCount) {
            return mapDvCount(dvCount);
        } else if (dataValue instanceof DvDuration dvDuration) {
            return mapDvDuration(dvDuration);
        } else if (dataValue instanceof DvProportion dvProportion) {
            return mapDvProportion(dvProportion);
        } else if (dataValue instanceof DvOrdinal dvOrdinal) {
            return mapDvOrdinal(dvOrdinal);
        } else if (dataValue instanceof DvIdentifier dvIdentifier) {
            return mapDvIdentifier(dvIdentifier);
        } else if (dataValue instanceof DvURI dvUri) {
            return mapDvUri(dvUri);
        } else if (dataValue instanceof DvParsable dvParsable) {
            return mapDvParsable(dvParsable);
        }

        // Fallback: convert to string representation
        return new StringType(dataValue.toString());
    }

    /**
     * Maps a {@link DvCodedText} to a FHIR {@link CodeableConcept}.
     */
    public static CodeableConcept mapDvCodedText(DvCodedText dvCodedText) {
        CodeableConcept concept = new CodeableConcept();
        concept.setText(dvCodedText.getValue());

        CodePhrase definingCode = dvCodedText.getDefiningCode();
        if (definingCode != null) {
            concept.addCoding(mapCodePhrase(definingCode, dvCodedText.getValue()));
        }

        return concept;
    }

    /**
     * Maps a {@link DvText} to a FHIR {@link StringType}.
     */
    public static StringType mapDvText(DvText dvText) {
        return new StringType(dvText.getValue());
    }

    /**
     * Maps a {@link DvQuantity} to a FHIR {@link Quantity}.
     */
    public static Quantity mapDvQuantity(DvQuantity dvQuantity) {
        Quantity quantity = new Quantity();
        if (dvQuantity.getMagnitude() != null) {
            quantity.setValue(BigDecimal.valueOf(dvQuantity.getMagnitude()));
        }
        if (dvQuantity.getUnits() != null) {
            quantity.setUnit(dvQuantity.getUnits());
            // UCUM is the standard code system for units in FHIR
            quantity.setSystem("http://unitsofmeasure.org");
            quantity.setCode(dvQuantity.getUnits());
        }
        return quantity;
    }

    /**
     * Maps a {@link DvDateTime} to a FHIR {@link DateTimeType}.
     */
    public static DateTimeType mapDvDateTime(DvDateTime dvDateTime) {
        if (dvDateTime.getValue() == null) {
            return new DateTimeType();
        }

        TemporalAccessor temporal = dvDateTime.getValue();
        Date date = convertTemporalToDate(temporal);
        if (date != null) {
            return new DateTimeType(date);
        }
        // Fallback: use the string representation
        return new DateTimeType(temporal.toString());
    }

    /**
     * Maps a {@link DvDate} to a FHIR {@link DateType}.
     */
    public static DateType mapDvDate(DvDate dvDate) {
        if (dvDate.getValue() == null) {
            return new DateType();
        }

        TemporalAccessor temporal = dvDate.getValue();
        if (temporal instanceof LocalDate localDate) {
            return new DateType(localDate.getYear() + "-" + padTwo(localDate.getMonthValue()) + "-"
                    + padTwo(localDate.getDayOfMonth()));
        }
        return new DateType(temporal.toString());
    }

    /**
     * Maps a {@link DvTime} to a FHIR {@link TimeType}.
     */
    public static TimeType mapDvTime(DvTime dvTime) {
        if (dvTime.getValue() == null) {
            return new TimeType();
        }
        return new TimeType(dvTime.getValue().toString());
    }

    /**
     * Maps a {@link DvBoolean} to a FHIR {@link BooleanType}.
     */
    public static BooleanType mapDvBoolean(DvBoolean dvBoolean) {
        return new BooleanType(dvBoolean.getValue());
    }

    /**
     * Maps a {@link DvCount} to a FHIR {@link IntegerType}.
     */
    public static IntegerType mapDvCount(DvCount dvCount) {
        if (dvCount.getMagnitude() != null) {
            return new IntegerType(dvCount.getMagnitude().intValue());
        }
        return new IntegerType();
    }

    /**
     * Maps a {@link DvDuration} to a FHIR {@link StringType} containing an ISO 8601 duration.
     */
    public static StringType mapDvDuration(DvDuration dvDuration) {
        if (dvDuration.getValue() == null) {
            return new StringType();
        }
        return new StringType(dvDuration.getValue().toString());
    }

    /**
     * Maps a {@link DvProportion} to a FHIR {@link Ratio}.
     */
    public static Ratio mapDvProportion(DvProportion dvProportion) {
        Ratio ratio = new Ratio();
        if (dvProportion.getNumerator() != null) {
            Quantity numerator = new Quantity();
            numerator.setValue(BigDecimal.valueOf(dvProportion.getNumerator()));
            ratio.setNumerator(numerator);
        }
        if (dvProportion.getDenominator() != null) {
            Quantity denominator = new Quantity();
            denominator.setValue(BigDecimal.valueOf(dvProportion.getDenominator()));
            ratio.setDenominator(denominator);
        }
        return ratio;
    }

    /**
     * Maps a {@link DvOrdinal} to a FHIR {@link CodeableConcept} preserving the ordinal value
     * and its coded symbol.
     */
    public static CodeableConcept mapDvOrdinal(DvOrdinal dvOrdinal) {
        if (dvOrdinal.getSymbol() != null) {
            return mapDvCodedText(dvOrdinal.getSymbol());
        }
        CodeableConcept concept = new CodeableConcept();
        if (dvOrdinal.getValue() != null) {
            concept.setText("ordinal:" + dvOrdinal.getValue());
        }
        return concept;
    }

    /**
     * Maps a {@link DvIdentifier} to a FHIR {@link Identifier}.
     */
    public static Identifier mapDvIdentifier(DvIdentifier dvIdentifier) {
        Identifier identifier = new Identifier();
        identifier.setValue(dvIdentifier.getId());
        if (dvIdentifier.getIssuer() != null) {
            identifier.setSystem(dvIdentifier.getIssuer());
        }
        if (dvIdentifier.getType() != null) {
            identifier.setType(new CodeableConcept().setText(dvIdentifier.getType()));
        }
        return identifier;
    }

    /**
     * Maps a {@link DvURI} to a FHIR {@link UriType}.
     */
    public static UriType mapDvUri(DvURI dvUri) {
        if (dvUri.getValue() == null) {
            return new UriType();
        }
        return new UriType(dvUri.getValue().toString());
    }

    /**
     * Maps a {@link DvParsable} to a FHIR {@link StringType}.
     */
    public static StringType mapDvParsable(DvParsable dvParsable) {
        return new StringType(dvParsable.getValue());
    }

    /**
     * Maps an openEHR {@link CodePhrase} to a FHIR {@link Coding}.
     *
     * @param codePhrase the openEHR code phrase
     * @param displayText optional display text for the coding
     * @return the corresponding FHIR Coding
     */
    public static Coding mapCodePhrase(CodePhrase codePhrase, String displayText) {
        Coding coding = new Coding();
        if (codePhrase.getTerminologyId() != null) {
            coding.setSystem(
                    mapTerminologyIdToFhirSystem(codePhrase.getTerminologyId().getValue()));
        }
        coding.setCode(codePhrase.getCodeString());
        if (displayText != null) {
            coding.setDisplay(displayText);
        }
        return coding;
    }

    /**
     * Maps an openEHR {@link PartyProxy} (typically the composer or subject) to a FHIR
     * {@link Reference}.
     *
     * @param partyProxy the openEHR party proxy
     * @return a FHIR Reference with display text derived from the party name
     */
    public static Reference mapPartyProxy(PartyProxy partyProxy) {
        Reference reference = new Reference();
        if (partyProxy instanceof PartyIdentified partyIdentified) {
            if (partyIdentified.getName() != null) {
                reference.setDisplay(partyIdentified.getName());
            }
            if (partyIdentified.getExternalRef() != null
                    && partyIdentified.getExternalRef().getId() != null) {
                reference.setReference(
                        "Patient/" + partyIdentified.getExternalRef().getId().getValue());
            }
        }
        return reference;
    }

    /**
     * Maps common openEHR terminology IDs to their FHIR system URIs.
     */
    static String mapTerminologyIdToFhirSystem(String terminologyId) {
        if (terminologyId == null) {
            return null;
        }
        return switch (terminologyId) {
            case "SNOMED-CT", "SNOMED_CT" -> "http://snomed.info/sct";
            case "LOINC" -> "http://loinc.org";
            case "ICD10", "ICD-10" -> "http://hl7.org/fhir/sid/icd-10";
            case "ICD10-CM", "ICD-10-CM" -> "http://hl7.org/fhir/sid/icd-10-cm";
            case "ICD9", "ICD-9" -> "http://hl7.org/fhir/sid/icd-9-cm";
            case "RXNORM", "RxNorm" -> "http://www.nlm.nih.gov/research/umls/rxnorm";
            case "openehr" -> "http://openehr.org/id";
            case "local" -> "http://openehr.org/local";
            default -> terminologyId;
        };
    }

    private static Date convertTemporalToDate(TemporalAccessor temporal) {
        if (temporal instanceof OffsetDateTime odt) {
            return Date.from(odt.toInstant());
        } else if (temporal instanceof LocalDateTime ldt) {
            return Date.from(ldt.toInstant(java.time.ZoneOffset.UTC));
        } else if (temporal instanceof LocalDate ld) {
            return Date.from(ld.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        }
        return null;
    }

    private static String padTwo(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
