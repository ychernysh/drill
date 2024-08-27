package org.apache.drill;

import org.apache.drill.common.types.TypeProtos;
import org.apache.drill.common.types.Types;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.record.BatchSchemaBuilder;
import org.apache.drill.exec.record.metadata.SchemaBuilder;
import org.apache.drill.test.ClusterFixture;
import org.apache.drill.test.ClusterTest;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Paths;

/**
 * Covers querying a table in which some parquet files do contain selected columns, and
 * others do not (or have them as OPTIONALs).
 *
 * Expected behavior for the missing columns is following:
 * 1) If at least 1 parquet file to be read has the column, take the minor type from there.
 * Otherwise, default to INT.
 * 2) If at least 1 parquet file to be read doesn't have the column, or has it as OPTIONAL,
 * enforce the overall scan output schema to have it as OPTIONAL
 *
 * We need to control ordering of scanning batches to cover different erroneous cases, and we assume
 * that parquet files in a table would be read in alphabetic order (not a real use case though). So
 * we name our files 0.parquet and 1.parquet expecting that they would be scanned in that order
 * (not guaranteed though, but seems to work). We use such tables for such scenarios:
 *
 * - parquet/partially_missing/o_m -- optional, then missing
 * - parquet/partially_missing/m_o -- missing, then optional
 * - parquet/partially_missing/r_m -- required, then missing
 * - parquet/partially_missing/r_o -- required, then optional
 *
 * These tables have these parquet files with such schemas:
 *
 * - parquet/partially_missing/o_m/0.parquet: id<INT(REQUIRED)> | name<VARCHAR(OPTIONAL)> | age<INT(OPTIONAL)>
 * - parquet/partially_missing/o_m/1.parquet: id<INT(REQUIRED)>
 *
 * - parquet/partially_missing/m_0/0.parquet: id<INT(REQUIRED)>
 * - parquet/partially_missing/m_0/1.parquet: id<INT(REQUIRED)> | name<VARCHAR(OPTIONAL)> | age<INT(OPTIONAL)>
 *
 * - parquet/partially_missing/r_m/0.parquet: id<INT(REQUIRED)> | name<VARCHAR(REQUIRED)> | age<INT(REQUIRED)>
 * - parquet/partially_missing/r_m/1.parquet: id<INT(REQUIRED)>
 *
 * - parquet/partially_missing/r_o/0.parquet: id<INT(REQUIRED)> | name<VARCHAR(REQUIRED)> | age<INT(REQUIRED)>
 * - parquet/partially_missing/r_o/1.parquet: id<INT(REQUIRED)> | name<VARCHAR(OPTIONAL)> | age<INT(OPTIONAL)>
 *
 * So, by querying "age" or "name" columns we would trigger both 0.parquet reader to read the data and
 * 1.parquet reader to create the missing column vector.
 */
public class TestParquetPartiallyMissingColumns extends ClusterTest {

  private static final SchemaBuilder ageSchema =
      new SchemaBuilder().add("age", Types.optional(TypeProtos.MinorType.INT));
  private static final SchemaBuilder nameSchema =
      new SchemaBuilder().add("name", Types.optional(TypeProtos.MinorType.VARCHAR));

  @BeforeClass
  public static void setup() throws Exception {
    startCluster(ClusterFixture.builder(dirTestWatcher));
    dirTestWatcher.copyResourceToRoot(Paths.get("parquet", "partially_missing"));
  }

  /*
  Field name for the missing column MUST NOT be quoted with back-ticks, so we should have ONLY ONE
  column for that field (unquoted)
   */

  @Test
  public void testMissingColumnNamingWithOrderBy() throws Exception {
    test("SELECT age FROM dfs.`parquet/partially_missing/o_m` ORDER BY age", ageSchema);
  }

  @Test
  public void testMissingColumnNamingWithUnionAll() throws Exception {
    test("SELECT age FROM dfs.`parquet/partially_missing/o_m` UNION ALL (VALUES (1))", ageSchema);
  }

  /*
  If at least 1 file in the table has the selected column, the overall scan output schema should
  take the MinorType for the column from there (and not default to Int)
   */

  @Test
  public void testMissingColumnTypeGuessWithOrderBy() throws Exception {
    test("SELECT name FROM dfs.`parquet/partially_missing/o_m` ORDER BY name", nameSchema);
  }

  @Test
  public void testMissingColumnTypeGuessWithUnionAll() throws Exception {
    test("SELECT name FROM dfs.`parquet/partially_missing/m_o` UNION ALL (VALUES ('Bob'))", nameSchema);
  }

  /*
  If at least 1 file in the table doesn't have the selected column, or has it as OPTIONAL,
  the overall scan output schema should have this column as OPTIONAL
   */

  @Test
  public void testEnforcingOptionalWithOrderBy() throws Exception {
    test("SELECT age FROM dfs.`parquet/partially_missing/r_o` ORDER BY age", ageSchema);
    test("SELECT age FROM dfs.`parquet/partially_missing/r_m` ORDER BY age", ageSchema);
    test("SELECT name FROM dfs.`parquet/partially_missing/r_o` ORDER BY name", nameSchema);
    test("SELECT name FROM dfs.`parquet/partially_missing/r_m` ORDER BY name", nameSchema);
  }

  @Test
  public void testEnforcingOptionalWithUnionAll() throws Exception {
    test("SELECT age FROM dfs.`parquet/partially_missing/r_o` UNION ALL (VALUES (1))", ageSchema);
    test("SELECT age FROM dfs.`parquet/partially_missing/r_m` UNION ALL (VALUES (1))", ageSchema);
    test("SELECT name FROM dfs.`parquet/partially_missing/r_o` UNION ALL (VALUES ('Bob'))", nameSchema);
    test("SELECT name FROM dfs.`parquet/partially_missing/r_m` UNION ALL (VALUES ('Bob'))", nameSchema);
  }

  // Runs the query and verifies the result schema against the expected schema
  private void test(String query, SchemaBuilder expectedSchemaBuilder) throws Exception {
    BatchSchema expectedSchema = new BatchSchemaBuilder()
        .withSchemaBuilder(expectedSchemaBuilder)
        .build();

    testBuilder()
        .sqlQuery(query)
        .schemaBaseLine(expectedSchema)
        .go();
  }

}
