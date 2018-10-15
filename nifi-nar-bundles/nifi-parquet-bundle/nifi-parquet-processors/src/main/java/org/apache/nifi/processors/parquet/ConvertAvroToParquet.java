package org.apache.nifi.processors.parquet;


import com.google.common.collect.ImmutableSet;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.parquet.stream.NifiParquetOutputFile;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.avro.AvroReadSupport;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Tags({"avro", "parquet", "convert"})
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@CapabilityDescription("Converts Avro records into Parquet file format. The incoming FlowFile should be a valid avro file. If an incoming FlowFile does "
        + "not contain any records, an empty parquet file is the output. NOTE: Many Avro datatypes (collections, primitives, and unions of primitives, e.g.) can "
        + "be converted to parquet, but unions of collections and other complex datatypes may not be able to be converted to Parquet.")
@WritesAttributes({
        @WritesAttribute(attribute = "filename", description = "Sets the filename to the existing filename with the extension replaced by / added to by .parquet"),
        @WritesAttribute(attribute = "record.count", description = "Sets the number of records in the parquet file.")
})
public class ConvertAvroToParquet extends AbstractProcessor {

    // Attributes
    public static final String FILE_NAME_ATTRIBUTE = "file.name";
    public static final String RECORD_COUNT_ATTRIBUTE = "record.count";

    public static final List<AllowableValue> COMPRESSION_TYPES;
    static {
        final List<AllowableValue> compressionTypes = new ArrayList<>();
        for (CompressionCodecName compressionCodecName : CompressionCodecName.values()) {
            final String name = compressionCodecName.name();
            compressionTypes.add(new AllowableValue(name, name));
        }
        COMPRESSION_TYPES = Collections.unmodifiableList(compressionTypes);
    }


    // Parquet properties
    public static final PropertyDescriptor COMPRESSION_TYPE = new PropertyDescriptor.Builder()
            .name("compression-type")
            .displayName("Compression Type")
            .description("The type of compression for the file being written.")
            .allowableValues(COMPRESSION_TYPES.toArray(new AllowableValue[0]))
            .defaultValue(COMPRESSION_TYPES.get(0).getValue())
            .required(true)
            .build();


    public static final PropertyDescriptor ROW_GROUP_SIZE = new PropertyDescriptor.Builder()
            .name("row-group-size")
            .displayName("Row Group Size")
            .description("The row group size used by the Parquet writer. " +
                    "The value is specified in the format of <Data Size> <Data Unit> where Data Unit is one of B, KB, MB, GB, TB.")
            .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor PAGE_SIZE = new PropertyDescriptor.Builder()
            .name("page-size")
            .displayName("Page Size")
            .description("The page size used by the Parquet writer. " +
                    "The value is specified in the format of <Data Size> <Data Unit> where Data Unit is one of B, KB, MB, GB, TB.")
            .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor DICTIONARY_PAGE_SIZE = new PropertyDescriptor.Builder()
            .name("dictionary-page-size")
            .displayName("Dictionary Page Size")
            .description("The dictionary page size used by the Parquet writer. " +
                    "The value is specified in the format of <Data Size> <Data Unit> where Data Unit is one of B, KB, MB, GB, TB.")
            .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor MAX_PADDING_SIZE = new PropertyDescriptor.Builder()
            .name("max-padding-size")
            .displayName("Max Padding Size")
            .description("The maximum amount of padding that will be used to align row groups with blocks in the " +
                    "underlying filesystem. If the underlying filesystem is not a block filesystem like HDFS, this has no effect. " +
                    "The value is specified in the format of <Data Size> <Data Unit> where Data Unit is one of B, KB, MB, GB, TB.")
            .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor ENABLE_DICTIONARY_ENCODING = new PropertyDescriptor.Builder()
            .name("enable-dictionary-encoding")
            .displayName("Enable Dictionary Encoding")
            .description("Specifies whether dictionary encoding should be enabled for the Parquet writer")
            .allowableValues("true", "false")
            .build();

    public static final PropertyDescriptor ENABLE_VALIDATION = new PropertyDescriptor.Builder()
            .name("enable-validation")
            .displayName("Enable Validation")
            .description("Specifies whether validation should be enabled for the Parquet writer")
            .allowableValues("true", "false")
            .build();

    public static final PropertyDescriptor WRITER_VERSION = new PropertyDescriptor.Builder()
            .name("writer-version")
            .displayName("Writer Version")
            .description("Specifies the version used by Parquet writer")
            .allowableValues(ParquetProperties.WriterVersion.values())
            .build();

    public static final PropertyDescriptor REMOVE_CRC_FILES = new PropertyDescriptor.Builder()
            .name("remove-crc-files")
            .displayName("Remove CRC Files")
            .description("Specifies whether the corresponding CRC file should be deleted upon successfully writing a Parquet file")
            .allowableValues("true", "false")
            .defaultValue("false")
            .build();

    private volatile List<PropertyDescriptor> parquetProps;

    // Relationships
    static final Relationship SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Parquet file that was converted successfully from Avro")
            .build();

    static final Relationship FAILURE = new Relationship.Builder()
            .name("failure")
            .description("Avro content that could not be processed")
            .build();

    static final Set<Relationship> RELATIONSHIPS
            = ImmutableSet.<Relationship>builder()
            .add(SUCCESS)
            .add(FAILURE)
            .build();

    @Override
    protected final void init(final ProcessorInitializationContext context) {


        final List<PropertyDescriptor> props = new ArrayList<>();

        props.add(COMPRESSION_TYPE);
        props.add(ROW_GROUP_SIZE);
        props.add(PAGE_SIZE);
        props.add(DICTIONARY_PAGE_SIZE);
        props.add(MAX_PADDING_SIZE);
        props.add(ENABLE_DICTIONARY_ENCODING);
        props.add(ENABLE_VALIDATION);
        props.add(WRITER_VERSION);
        props.add(REMOVE_CRC_FILES);

        this.parquetProps = Collections.unmodifiableList(props);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return parquetProps;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {

        final FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        try {

            long startTime = System.currentTimeMillis();
            final AtomicInteger totalRecordCount = new AtomicInteger(0);

            final String fileName = flowFile.getAttribute(CoreAttributes.FILENAME.key());

            FlowFile putFlowFile = flowFile;

            putFlowFile = session.write(flowFile, (rawIn, rawOut) -> {
                try (final InputStream in = new BufferedInputStream(rawIn);
                     final OutputStream out = new BufferedOutputStream(rawOut);
                     final DataFileStream<GenericRecord> dataFileReader = new DataFileStream<>(in, new GenericDatumReader<>())) {

                    Schema avroSchema = dataFileReader.getSchema();
                    System.out.println(avroSchema.toString(true));
                    ParquetWriter<GenericRecord> writer = createParquetWriter2(context, flowFile, rawOut, avroSchema );

                    try {
                        int recordCount = 0;
                        GenericRecord record = null;
                        while (dataFileReader.hasNext()) {
                            record = dataFileReader.next();
                            writer.write(record);
                            recordCount++;
                        }
                        totalRecordCount.set(recordCount);
                    } finally {
                        writer.close();
                    }
                }
            });


            // Add attributes and transfer to success
            putFlowFile = session.putAttribute(putFlowFile, RECORD_COUNT_ATTRIBUTE, Integer.toString(totalRecordCount.get()));
            StringBuilder newFilename = new StringBuilder();
            int extensionIndex = fileName.lastIndexOf(".");
            if (extensionIndex != -1) {
                newFilename.append(fileName.substring(0, extensionIndex));
            } else {
                newFilename.append(fileName);
            }
            newFilename.append(".parquet");
            putFlowFile = session.putAttribute(putFlowFile, CoreAttributes.FILENAME.key(), newFilename.toString());
            session.transfer(putFlowFile, SUCCESS);
            session.getProvenanceReporter().modifyContent(putFlowFile, "Converted "+totalRecordCount.get()+" records", System.currentTimeMillis() - startTime);

        } catch (final ProcessException pe) {
            getLogger().error("Failed to convert {} from Avro to Parquet due to {}; transferring to failure", new Object[]{flowFile, pe});
            session.transfer(flowFile, FAILURE);
        }

    }

    private ParquetWriter createParquetWriter2(final ProcessContext context, final FlowFile flowFile, final OutputStream out, final Schema schema)
            throws IOException {

        NifiParquetOutputFile nifiParquetOutputFile = new NifiParquetOutputFile(out);

        final AvroParquetWriter.Builder<GenericRecord> parquetWriter = AvroParquetWriter
                .<GenericRecord>builder(nifiParquetOutputFile)
                .withSchema(schema);

        Configuration conf = new Configuration();
        conf.setBoolean(AvroReadSupport.AVRO_COMPATIBILITY, true);
        conf.setBoolean("parquet.avro.add-list-element-records", false);
        conf.setBoolean("parquet.avro.write-old-list-structure", false);

        applyCommonConfig(parquetWriter, context, flowFile, conf);

        return parquetWriter.build();
    }

    private void applyCommonConfig(final ParquetWriter.Builder<?, ?> builder, final ProcessContext context, final FlowFile flowFile, final Configuration conf) {
        builder.withConf(conf);

        // Required properties

        final ParquetFileWriter.Mode mode = ParquetFileWriter.Mode.CREATE;
        builder.withWriteMode(mode);

        final PropertyDescriptor compressionTypeDescriptor = getPropertyDescriptor(COMPRESSION_TYPE.getName());

        final String compressionTypeValue = context.getProperty(compressionTypeDescriptor).getValue();

        final CompressionCodecName codecName = CompressionCodecName.valueOf(compressionTypeValue);
        builder.withCompressionCodec(codecName);

        // Optional properties

        if (context.getProperty(ROW_GROUP_SIZE).isSet()){
            try {
                final Double rowGroupSize = context.getProperty(ROW_GROUP_SIZE).evaluateAttributeExpressions(flowFile).asDataSize(DataUnit.B);
                if (rowGroupSize != null) {
                    builder.withRowGroupSize(rowGroupSize.intValue());
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid data size for " + ROW_GROUP_SIZE.getDisplayName(), e);
            }
        }

        if (context.getProperty(PAGE_SIZE).isSet()) {
            try {
                final Double pageSize = context.getProperty(PAGE_SIZE).evaluateAttributeExpressions(flowFile).asDataSize(DataUnit.B);
                if (pageSize != null) {
                    builder.withPageSize(pageSize.intValue());
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid data size for " + PAGE_SIZE.getDisplayName(), e);
            }
        }

        if (context.getProperty(DICTIONARY_PAGE_SIZE).isSet()) {
            try {
                final Double dictionaryPageSize = context.getProperty(DICTIONARY_PAGE_SIZE).evaluateAttributeExpressions(flowFile).asDataSize(DataUnit.B);
                if (dictionaryPageSize != null) {
                    builder.withDictionaryPageSize(dictionaryPageSize.intValue());
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid data size for " + DICTIONARY_PAGE_SIZE.getDisplayName(), e);
            }
        }

        if (context.getProperty(MAX_PADDING_SIZE).isSet()) {
            try {
                final Double maxPaddingSize = context.getProperty(MAX_PADDING_SIZE).evaluateAttributeExpressions(flowFile).asDataSize(DataUnit.B);
                if (maxPaddingSize != null) {
                    builder.withMaxPaddingSize(maxPaddingSize.intValue());
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid data size for " + MAX_PADDING_SIZE.getDisplayName(), e);
            }
        }

        if (context.getProperty(ENABLE_DICTIONARY_ENCODING).isSet()) {
            final boolean enableDictionaryEncoding = context.getProperty(ENABLE_DICTIONARY_ENCODING).asBoolean();
            builder.withDictionaryEncoding(enableDictionaryEncoding);
        }

        if (context.getProperty(ENABLE_VALIDATION).isSet()) {
            final boolean enableValidation = context.getProperty(ENABLE_VALIDATION).asBoolean();
            builder.withValidation(enableValidation);
        }

        if (context.getProperty(WRITER_VERSION).isSet()) {
            final String writerVersionValue = context.getProperty(WRITER_VERSION).getValue();
            builder.withWriterVersion(ParquetProperties.WriterVersion.valueOf(writerVersionValue));
        }
    }

}
