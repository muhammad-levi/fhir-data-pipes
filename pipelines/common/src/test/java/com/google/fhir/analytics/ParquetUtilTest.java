/*
 * Copyright 2020-2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.analytics;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.number.IsCloseTo.closeTo;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.parser.IParser;
import com.google.common.io.Resources;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO add testes for DSTU3 resources too.

@RunWith(MockitoJUnitRunner.class)
public class ParquetUtilTest {

  private static final Logger log = LoggerFactory.getLogger(ParquetUtil.class);

  private static final String PARQUET_ROOT = "parquet_root";

  private String patientBundle;

  private String observationBundle;

  private ParquetUtil parquetUtil;

  private FhirContext fhirContext;

  private Path rootPath;

  @Rule public TemporaryFolder testFolder = new TemporaryFolder();

  @Before
  public void setup() throws IOException {
    File rootFolder = testFolder.newFolder(PARQUET_ROOT);
    rootPath = Paths.get(rootFolder.getPath());
    Files.createDirectories(rootPath);
    ParquetUtil.initializeAvroConverters();
    patientBundle =
        Resources.toString(Resources.getResource("patient_bundle.json"), StandardCharsets.UTF_8);
    observationBundle =
        Resources.toString(
            Resources.getResource("observation_bundle.json"), StandardCharsets.UTF_8);
    this.fhirContext = FhirContext.forR4Cached();
    parquetUtil = new ParquetUtil(FhirVersionEnum.R4, rootPath.toString(), 0, 0, "TEST_");
  }

  @Test
  public void getResourceSchema_Patient() {
    Schema schema = parquetUtil.getResourceSchema("Patient", fhirContext);
    assertThat(schema.getField("id").toString(), notNullValue());
    assertThat(schema.getField("identifier").toString(), notNullValue());
    assertThat(schema.getField("name").toString(), notNullValue());
  }

  @Test
  public void getResourceSchema_Observation() {
    Schema schema = parquetUtil.getResourceSchema("Observation", fhirContext);
    assertThat(schema.getField("id").toString(), notNullValue());
    assertThat(schema.getField("identifier").toString(), notNullValue());
    assertThat(schema.getField("basedOn").toString(), notNullValue());
    assertThat(schema.getField("subject").toString(), notNullValue());
    assertThat(schema.getField("code").toString(), notNullValue());
  }

  @Test
  public void getResourceSchema_Encounter() {
    Schema schema = parquetUtil.getResourceSchema("Encounter", fhirContext);
    assertThat(schema.getField("id").toString(), notNullValue());
    assertThat(schema.getField("identifier").toString(), notNullValue());
    assertThat(schema.getField("status").toString(), notNullValue());
    assertThat(schema.getField("class").toString(), notNullValue());
  }

  @Test
  public void generateRecords_BundleOfPatients() {
    IParser parser = fhirContext.newJsonParser();
    Bundle bundle = parser.parseResource(Bundle.class, patientBundle);
    List<GenericRecord> recordList = parquetUtil.generateRecords(bundle);
    assertThat(recordList.size(), equalTo(1));
    assertThat(recordList.get(0).get("address"), notNullValue());
    Collection<Object> addressList = (Collection<Object>) recordList.get(0).get("address");
    assertThat(addressList.size(), equalTo(1));
    Record address = (Record) addressList.iterator().next();
    assertThat((String) address.get("city"), equalTo("Waterloo"));
  }

  @Test
  public void generateRecords_BundleOfObservations() {
    IParser parser = fhirContext.newJsonParser();
    Bundle bundle = parser.parseResource(Bundle.class, observationBundle);
    List<GenericRecord> recordList = parquetUtil.generateRecords(bundle);
    assertThat(recordList.size(), equalTo(6));
  }

  @Test
  public void generateRecordForPatient() {
    IParser parser = fhirContext.newJsonParser();
    Bundle bundle = parser.parseResource(Bundle.class, patientBundle);
    GenericRecord record = parquetUtil.convertToAvro(bundle.getEntry().get(0).getResource());
    Collection<Object> addressList = (Collection<Object>) record.get("address");
    // TODO We need to fix this again; the root cause is the change in `id` type to System.String.
    // https://github.com/GoogleCloudPlatform/openmrs-fhir-analytics/issues/55
    // assertThat(record.get("id"), equalTo("471be3bc-08c7-4d78-a4ab-1b3d044dae67"));
    assertThat(addressList.size(), equalTo(1));
    Record address = (Record) addressList.iterator().next();
    assertThat((String) address.get("city"), equalTo("Waterloo"));
  }

  @Test
  public void bestOutputFile_NoDir() throws IOException {
    ResourceId bestFile = parquetUtil.getUniqueOutputFilePath("Patient");
    String fileSeparator = DwhFiles.getFileSeparatorForDwhFiles(rootPath.toString());

    String path = rootPath.toString() + fileSeparator + "Patient" + fileSeparator;
    path = path.replaceAll(Matcher.quoteReplacement("\\"), Matcher.quoteReplacement("\\\\"));
    String patternToBeMatched =
        path
            + "TEST_Patient_output-parquet-th-[\\p{Digit}]+-ts-[\\p{Digit}]+-r-[\\p{Digit}]+.parquet";
    assertThat(bestFile.toString(), matchesPattern(patternToBeMatched));
  }

  @Test
  public void bestOutputFile_NoFiles() throws IOException {
    Path patientPath = rootPath.resolve("Patient");
    Files.createDirectory(patientPath);
    ResourceId bestFile = parquetUtil.getUniqueOutputFilePath("Patient");
    String fileSeparator = DwhFiles.getFileSeparatorForDwhFiles(rootPath.toString());

    String path = rootPath.toString() + fileSeparator + "Patient" + fileSeparator;
    path = path.replaceAll(Matcher.quoteReplacement("\\"), Matcher.quoteReplacement("\\\\"));
    String patternToBeMatched =
        path
            + "TEST_Patient_output-parquet-th-[\\p{Digit}]+-ts-[\\p{Digit}]+-r-[\\p{Digit}]+.parquet";
    assertThat(bestFile.toString(), matchesPattern(patternToBeMatched));
  }

  @Test
  public void createSingleOutput() throws IOException {
    rootPath = Files.createTempDirectory("PARQUET_TEST");
    parquetUtil = new ParquetUtil(FhirVersionEnum.R4, rootPath.toString(), 0, 0, "");
    IParser parser = fhirContext.newJsonParser();
    Bundle bundle = parser.parseResource(Bundle.class, observationBundle);
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      parquetUtil.write(entry.getResource());
    }
    parquetUtil.closeAllWriters();
    String fileSeparator = DwhFiles.getFileSeparatorForDwhFiles(rootPath.toString());
    Stream<Path> files =
        Files.list(rootPath.resolve("Observation"))
            .filter(
                f ->
                    f.toString()
                        .startsWith(
                            rootPath.toString()
                                + fileSeparator
                                + "Observation"
                                + fileSeparator
                                + "Observation_output-"));
    assertThat(files.count(), equalTo(1L));
  }

  @Test
  public void createMultipleOutputByTime() throws IOException, InterruptedException {
    rootPath = Files.createTempDirectory("PARQUET_TEST");
    parquetUtil = new ParquetUtil(FhirVersionEnum.R4, rootPath.toString(), 1, 0, "");
    IParser parser = fhirContext.newJsonParser();
    Bundle bundle = parser.parseResource(Bundle.class, observationBundle);
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      parquetUtil.write(entry.getResource());
      TimeUnit.SECONDS.sleep(2); // A better way to test this is to inject a mocked `Timer`.
    }
    parquetUtil.closeAllWriters();
    String fileSeparator = DwhFiles.getFileSeparatorForDwhFiles(rootPath.toString());
    Stream<Path> files =
        Files.list(rootPath.resolve("Observation"))
            .filter(
                f ->
                    f.toString()
                        .startsWith(
                            rootPath.toString()
                                + fileSeparator
                                + "Observation"
                                + fileSeparator
                                + "Observation_output-"));
    assertThat(files.count(), equalTo(7L));
  }

  @Test
  public void createSingleOutputWithRowGroupSize() throws IOException {
    rootPath = Files.createTempDirectory("PARQUET_TEST");
    parquetUtil = new ParquetUtil(FhirVersionEnum.R4, rootPath.toString(), 0, 1, "");
    IParser parser = fhirContext.newJsonParser();
    Bundle bundle = parser.parseResource(Bundle.class, observationBundle);
    // There are 7 resources in the bundle so we write 15*7 (>100) resources, such that the page
    // group size check is triggered but still it is expected to generate one file only.
    for (int i = 0; i < 15; i++) {
      for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
        parquetUtil.write(entry.getResource());
      }
    }
    parquetUtil.closeAllWriters();
    String fileSeparator = DwhFiles.getFileSeparatorForDwhFiles(rootPath.toString());
    Stream<Path> files =
        Files.list(rootPath.resolve("Observation"))
            .filter(
                f ->
                    f.toString()
                        .startsWith(
                            rootPath.toString()
                                + fileSeparator
                                + "Observation"
                                + fileSeparator
                                + "Observation_output-"));
    assertThat(files.count(), equalTo(1L));
  }

  @Test
  public void convertObservationWithBigDecimalValue() throws IOException {
    String observationStr =
        Resources.toString(
            Resources.getResource("observation_decimal.json"), StandardCharsets.UTF_8);
    IParser parser = fhirContext.newJsonParser();
    Observation observation = parser.parseResource(Observation.class, observationStr);
    GenericRecord record = parquetUtil.convertToAvro(observation);
    GenericData.Record valueRecord = (GenericData.Record) record.get("value");
    BigDecimal value = (BigDecimal) ((GenericData.Record) valueRecord.get("quantity")).get("value");
    assertThat(value.doubleValue(), closeTo(25, 0.001));
  }

  /**
   * This is the test to demonstrate the BigDecimal conversion bug. See:
   * https://github.com/GoogleCloudPlatform/openmrs-fhir-analytics/issues/156
   */
  @Test
  public void writeObservationWithBigDecimalValue() throws IOException {
    rootPath = Files.createTempDirectory("PARQUET_TEST");
    parquetUtil = new ParquetUtil(FhirVersionEnum.R4, rootPath.toString(), 0, 0, "");
    String observationStr =
        Resources.toString(
            Resources.getResource("observation_decimal.json"), StandardCharsets.UTF_8);
    IParser parser = fhirContext.newJsonParser();
    Observation observation = parser.parseResource(Observation.class, observationStr);
    parquetUtil.write(observation);
  }

  /** This is similar to the above test but has more `decimal` examples with different scales. */
  @Test
  public void writeObservationBundleWithDecimalConversionIssue() throws IOException {
    rootPath = Files.createTempDirectory("PARQUET_TEST");
    parquetUtil = new ParquetUtil(FhirVersionEnum.R4, rootPath.toString(), 0, 0, "");
    String observationBundleStr =
        Resources.toString(
            Resources.getResource("observation_decimal_bundle.json"), StandardCharsets.UTF_8);
    IParser parser = fhirContext.newJsonParser();
    Bundle bundle = parser.parseResource(Bundle.class, observationBundleStr);
    parquetUtil.writeRecords(bundle, null);
  }
}
