package com.telco.pipeline.ingestion.kafka.serializers;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Serializes tower signal measurement maps to Avro binary format using the
 * TowerSignal schema. Includes a 5-byte header (magic byte + 4-byte schema ID)
 * for Confluent Schema Registry compatibility.
 *
 * Thread-safe: each call allocates a new encoder/output stream.
 */
public class TowerSignalAvroSerializer {

    private static final Logger LOG = LoggerFactory.getLogger(TowerSignalAvroSerializer.class);
    private static final byte MAGIC_BYTE = 0x0;
    private static final int SCHEMA_ID = 1001; // Registered schema ID

    private final Schema schema;
    private final DatumWriter<GenericRecord> writer;

    public TowerSignalAvroSerializer() {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("avro/tower_signal.avsc")) {
            if (is == null) {
                // Fall back to loading from schema directory
                this.schema = new Schema.Parser().parse(
                        getClass().getClassLoader().getResourceAsStream(
                                "tower_signal.avsc"));
            } else {
                this.schema = new Schema.Parser().parse(is);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load TowerSignal schema", e);
        }
        this.writer = new GenericDatumWriter<>(schema);
    }

    public TowerSignalAvroSerializer(Schema schema) {
        this.schema = schema;
        this.writer = new GenericDatumWriter<>(schema);
    }

    /**
     * Serialize a signal measurement map to Avro binary format.
     *
     * @param signalData map of field names to values
     * @return Avro binary bytes with schema registry header
     */
    public byte[] serialize(Map<String, Object> signalData) {
        GenericRecord record = mapToGenericRecord(signalData);
        return serializeRecord(record);
    }

    public byte[] serializeRecord(GenericRecord record) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            // Write Confluent wire format header
            out.write(MAGIC_BYTE);
            out.write(ByteBuffer.allocate(4).putInt(SCHEMA_ID).array());

            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(record, encoder);
            encoder.flush();

            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize tower signal", e);
        }
    }

    private GenericRecord mapToGenericRecord(Map<String, Object> data) {
        GenericRecord record = new GenericData.Record(schema);

        for (Schema.Field field : schema.getFields()) {
            String fieldName = field.name();
            Object value = data.get(fieldName);

            if (value != null) {
                Schema fieldSchema = resolveUnion(field.schema());
                record.put(fieldName, convertValue(value, fieldSchema));
            } else if (isNullable(field.schema())) {
                record.put(fieldName, null);
            }
        }

        return record;
    }

    private Object convertValue(Object value, Schema schema) {
        switch (schema.getType()) {
            case INT:
                return ((Number) value).intValue();
            case LONG:
                return ((Number) value).longValue();
            case FLOAT:
                return ((Number) value).floatValue();
            case DOUBLE:
                return ((Number) value).doubleValue();
            case STRING:
                return value.toString();
            case BOOLEAN:
                return (Boolean) value;
            case ENUM:
                return new GenericData.EnumSymbol(schema, value.toString());
            case MAP:
                return value;
            default:
                return value;
        }
    }

    private Schema resolveUnion(Schema schema) {
        if (schema.getType() == Schema.Type.UNION) {
            for (Schema s : schema.getTypes()) {
                if (s.getType() != Schema.Type.NULL) {
                    return s;
                }
            }
        }
        return schema;
    }

    private boolean isNullable(Schema schema) {
        if (schema.getType() == Schema.Type.UNION) {
            return schema.getTypes().stream()
                    .anyMatch(s -> s.getType() == Schema.Type.NULL);
        }
        return false;
    }

    public Schema getSchema() {
        return schema;
    }
}
