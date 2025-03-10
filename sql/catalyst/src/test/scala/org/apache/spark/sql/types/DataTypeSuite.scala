/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.types

import com.fasterxml.jackson.core.JsonParseException

import org.apache.spark.{SparkException, SparkFunSuite, SparkIllegalArgumentException}
import org.apache.spark.sql.catalyst.analysis.{caseInsensitiveResolution, caseSensitiveResolution}
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.catalyst.util.StringConcat
import org.apache.spark.sql.types.DataTypeTestUtils.{dayTimeIntervalTypes, yearMonthIntervalTypes}

class DataTypeSuite extends SparkFunSuite {

  test("construct an ArrayType") {
    val array = ArrayType(StringType)

    assert(ArrayType(StringType, true) === array)
  }

  test("construct an MapType") {
    val map = MapType(StringType, IntegerType)

    assert(MapType(StringType, IntegerType, true) === map)
  }

  test("construct with add") {
    val struct = (new StructType)
      .add("a", IntegerType, true)
      .add("b", LongType, false)
      .add("c", StringType, true)

    assert(StructField("b", LongType, false) === struct("b"))
  }

  test("construct with add from StructField") {
    // Test creation from StructField type
    val struct = (new StructType)
      .add(StructField("a", IntegerType, true))
      .add(StructField("b", LongType, false))
      .add(StructField("c", StringType, true))

    assert(StructField("b", LongType, false) === struct("b"))
  }

  test("construct with add from StructField with comments") {
    // Test creation from StructField using four different ways
    val struct = (new StructType)
      .add("a", "int", true, "test1")
      .add("b", StringType, true, "test3")
      .add(StructField("c", LongType, false).withComment("test4"))
      .add(StructField("d", LongType))

    assert(StructField("a", IntegerType, true).withComment("test1") == struct("a"))
    assert(StructField("b", StringType, true).withComment("test3") == struct("b"))
    assert(StructField("c", LongType, false).withComment("test4") == struct("c"))
    assert(StructField("d", LongType) == struct("d"))

    assert(struct("c").getComment() == Option("test4"))
    assert(struct("d").getComment().isEmpty)
  }

  test("construct with String DataType") {
    // Test creation with DataType as String
    val struct = (new StructType)
      .add("a", "int", true)
      .add("b", "long", false)
      .add("c", "string", true)

    assert(StructField("a", IntegerType, true) === struct("a"))
    assert(StructField("b", LongType, false) === struct("b"))
    assert(StructField("c", StringType, true) === struct("c"))
  }

  test("extract fields from a StructType") {
    val struct = StructType(
      StructField("a", IntegerType, true) ::
      StructField("b", LongType, false) ::
      StructField("c", StringType, true) ::
      StructField("d", FloatType, true) :: Nil)

    assert(StructField("b", LongType, false) === struct("b"))

    intercept[SparkIllegalArgumentException] {
      struct("e")
    }

    val expectedStruct = StructType(
      StructField("b", LongType, false) ::
      StructField("d", FloatType, true) :: Nil)

    assert(expectedStruct === struct(Set("b", "d")))
    intercept[SparkIllegalArgumentException] {
      struct(Set("b", "d", "e", "f"))
    }
  }

  test("extract field index from a StructType") {
    val struct = StructType(
      StructField("a", LongType) ::
      StructField("b", FloatType) :: Nil)

    assert(struct.fieldIndex("a") === 0)
    assert(struct.fieldIndex("b") === 1)

    intercept[SparkIllegalArgumentException] {
      struct.fieldIndex("non_existent")
    }
  }

  test("fieldsMap returns map of name to StructField") {
    val struct = StructType(
      StructField("a", LongType) ::
      StructField("b", FloatType) :: Nil)

    val mapped = StructType.fieldsMap(struct.fields)

    val expected = Map(
      "a" -> StructField("a", LongType),
      "b" -> StructField("b", FloatType))

    assert(mapped === expected)
  }

  test("fieldNames and names returns field names") {
    val struct = StructType(
      StructField("a", LongType) :: StructField("b", FloatType) :: Nil)

    assert(struct.fieldNames === Seq("a", "b"))
    assert(struct.names === Seq("a", "b"))
  }

  test("merge where right contains type conflict") {
    val left = StructType(
      StructField("a", LongType) ::
      StructField("b", FloatType) :: Nil)

    val right = StructType(
      StructField("b", LongType) :: Nil)

    checkError(
      exception = intercept[SparkException] {
        left.merge(right)
      },
      errorClass = "CANNOT_MERGE_INCOMPATIBLE_DATA_TYPE",
      parameters = Map("left" -> "\"FLOAT\"", "right" -> "\"BIGINT\""
      )
    )
  }

  test("existsRecursively") {
    val struct = StructType(
      StructField("a", LongType) ::
      StructField("b", FloatType) :: Nil)
    assert(struct.existsRecursively(_.isInstanceOf[LongType]))
    assert(struct.existsRecursively(_.isInstanceOf[StructType]))
    assert(!struct.existsRecursively(_.isInstanceOf[IntegerType]))

    val mapType = MapType(struct, StringType)
    assert(mapType.existsRecursively(_.isInstanceOf[LongType]))
    assert(mapType.existsRecursively(_.isInstanceOf[StructType]))
    assert(mapType.existsRecursively(_.isInstanceOf[StringType]))
    assert(mapType.existsRecursively(_.isInstanceOf[MapType]))
    assert(!mapType.existsRecursively(_.isInstanceOf[IntegerType]))

    val arrayType = ArrayType(mapType)
    assert(arrayType.existsRecursively(_.isInstanceOf[LongType]))
    assert(arrayType.existsRecursively(_.isInstanceOf[StructType]))
    assert(arrayType.existsRecursively(_.isInstanceOf[StringType]))
    assert(arrayType.existsRecursively(_.isInstanceOf[MapType]))
    assert(arrayType.existsRecursively(_.isInstanceOf[ArrayType]))
    assert(!arrayType.existsRecursively(_.isInstanceOf[IntegerType]))
  }

  test("SPARK-36224: Backwards compatibility test for NullType.json") {
    assert(DataType.fromJson("\"null\"") == NullType)
  }

  test("SPARK-42723: Parse timestamp_ltz as TimestampType") {
    assert(DataType.fromJson("\"timestamp_ltz\"") == TimestampType)
    val expectedStructType = StructType(Seq(StructField("ts", TimestampType)))
    assert(DataType.fromDDL("ts timestamp_ltz") == expectedStructType)
  }

  def checkDataTypeFromJson(dataType: DataType): Unit = {
    test(s"from Json - $dataType") {
      assert(DataType.fromJson(dataType.json) === dataType)
    }
  }

  def checkDataTypeFromDDL(dataType: DataType): Unit = {
    test(s"from DDL - $dataType") {
      val parsed = StructType.fromDDL(s"a ${dataType.sql}")
      val expected = new StructType().add("a", dataType)
      assert(DataTypeUtils.sameType(parsed, expected))
    }
  }

  checkDataTypeFromJson(NullType)
  checkDataTypeFromDDL(NullType)

  checkDataTypeFromJson(BooleanType)
  checkDataTypeFromDDL(BooleanType)

  checkDataTypeFromJson(ByteType)
  checkDataTypeFromDDL(ByteType)

  checkDataTypeFromJson(ShortType)
  checkDataTypeFromDDL(ShortType)

  checkDataTypeFromJson(IntegerType)
  checkDataTypeFromDDL(IntegerType)

  checkDataTypeFromJson(LongType)
  checkDataTypeFromDDL(LongType)

  checkDataTypeFromJson(FloatType)
  checkDataTypeFromDDL(FloatType)

  checkDataTypeFromJson(DoubleType)
  checkDataTypeFromDDL(DoubleType)

  checkDataTypeFromJson(DecimalType(10, 5))
  checkDataTypeFromDDL(DecimalType(10, 5))

  checkDataTypeFromJson(DecimalType.SYSTEM_DEFAULT)
  checkDataTypeFromDDL(DecimalType.SYSTEM_DEFAULT)

  checkDataTypeFromJson(DateType)
  checkDataTypeFromDDL(DateType)

  checkDataTypeFromJson(TimestampType)
  checkDataTypeFromDDL(TimestampType)

  checkDataTypeFromJson(TimestampNTZType)
  checkDataTypeFromDDL(TimestampNTZType)

  checkDataTypeFromJson(StringType)
  checkDataTypeFromDDL(StringType)

  checkDataTypeFromJson(BinaryType)
  checkDataTypeFromDDL(BinaryType)

  checkDataTypeFromJson(ArrayType(DoubleType, true))
  checkDataTypeFromDDL(ArrayType(DoubleType, true))

  checkDataTypeFromJson(ArrayType(StringType, false))
  checkDataTypeFromDDL(ArrayType(StringType, false))

  checkDataTypeFromJson(MapType(IntegerType, StringType, true))
  checkDataTypeFromDDL(MapType(IntegerType, StringType, true))

  checkDataTypeFromJson(MapType(IntegerType, ArrayType(DoubleType), false))
  checkDataTypeFromDDL(MapType(IntegerType, ArrayType(DoubleType), false))

  checkDataTypeFromJson(CharType(1))
  checkDataTypeFromDDL(CharType(1))

  checkDataTypeFromJson(VarcharType(10))
  checkDataTypeFromDDL(VarcharType(11))

  dayTimeIntervalTypes.foreach(checkDataTypeFromJson)
  yearMonthIntervalTypes.foreach(checkDataTypeFromJson)

  yearMonthIntervalTypes.foreach(checkDataTypeFromDDL)
  dayTimeIntervalTypes.foreach(checkDataTypeFromDDL)

  val metadata = new MetadataBuilder()
    .putString("name", "age")
    .build()
  val structType = StructType(Seq(
    StructField("a", IntegerType, nullable = true),
    StructField("b", ArrayType(DoubleType), nullable = false),
    StructField("c", DoubleType, nullable = false, metadata)))
  checkDataTypeFromJson(structType)
  checkDataTypeFromDDL(structType)

  test("fromJson throws an exception when given type string is invalid") {
    checkError(
      exception = intercept[SparkIllegalArgumentException] {
        DataType.fromJson(""""abcd"""")
      },
      errorClass = "_LEGACY_ERROR_TEMP_3251",
      parameters = Map("other" -> "abcd"))

    checkError(
      exception = intercept[SparkIllegalArgumentException] {
        DataType.fromJson("""{"abcd":"a"}""")
      },
      errorClass = "_LEGACY_ERROR_TEMP_3251",
      parameters = Map("other" -> """{"abcd":"a"}"""))

    checkError(
      exception = intercept[SparkIllegalArgumentException] {
        DataType.fromJson("""{"fields": [{"a":123}], "type": "struct"}""")
      },
      errorClass = "_LEGACY_ERROR_TEMP_3250",
      parameters = Map("other" -> """{"a":123}"""))

    // Malformed JSON string
    val message = intercept[JsonParseException] {
      DataType.fromJson("abcd")
    }.getMessage
    assert(message.contains("Unrecognized token 'abcd'"))
  }

  // SPARK-40820: fromJson with only name and type
  test("Deserialized and serialized schema without nullable or metadata in") {
    val schema =
      """
        |{
        |    "type": "struct",
        |    "fields": [
        |        {
        |            "name": "c1",
        |            "type": "string"
        |        }
        |    ]
        |}
        |""".stripMargin
    val dt = DataType.fromJson(schema)

    dt.simpleString equals "struct<c1:string>"
    dt.json equals
      """
        |{"type":"struct","fields":[{"name":"c1","type":"string","nullable":false,"metadata":{}}]}
        |""".stripMargin
  }

  def checkDefaultSize(dataType: DataType, expectedDefaultSize: Int): Unit = {
    test(s"Check the default size of $dataType") {
      assert(dataType.defaultSize === expectedDefaultSize)
    }
  }

  checkDefaultSize(NullType, 1)
  checkDefaultSize(BooleanType, 1)
  checkDefaultSize(ByteType, 1)
  checkDefaultSize(ShortType, 2)
  checkDefaultSize(IntegerType, 4)
  checkDefaultSize(LongType, 8)
  checkDefaultSize(FloatType, 4)
  checkDefaultSize(DoubleType, 8)
  checkDefaultSize(DecimalType(10, 5), 8)
  checkDefaultSize(DecimalType.SYSTEM_DEFAULT, 16)
  checkDefaultSize(DateType, 4)
  checkDefaultSize(TimestampType, 8)
  checkDefaultSize(TimestampNTZType, 8)
  checkDefaultSize(StringType, 20)
  checkDefaultSize(BinaryType, 100)
  checkDefaultSize(ArrayType(DoubleType, true), 8)
  checkDefaultSize(ArrayType(StringType, false), 20)
  checkDefaultSize(MapType(IntegerType, StringType, true), 24)
  checkDefaultSize(MapType(IntegerType, ArrayType(DoubleType), false), 12)
  checkDefaultSize(structType, 20)
  checkDefaultSize(CharType(5), 5)
  checkDefaultSize(CharType(100), 100)
  checkDefaultSize(VarcharType(5), 5)
  checkDefaultSize(VarcharType(10), 10)
  yearMonthIntervalTypes.foreach(checkDefaultSize(_, 4))
  dayTimeIntervalTypes.foreach(checkDefaultSize(_, 8))

  def checkEqualsIgnoreCompatibleNullability(
      from: DataType,
      to: DataType,
      expected: Boolean): Unit = {
    val testName =
      s"equalsIgnoreCompatibleNullability: (from: $from, to: $to)"
    test(testName) {
      assert(DataType.equalsIgnoreCompatibleNullability(from, to) === expected)
    }
  }

  checkEqualsIgnoreCompatibleNullability(
    from = ArrayType(DoubleType, containsNull = true),
    to = ArrayType(DoubleType, containsNull = true),
    expected = true)
  checkEqualsIgnoreCompatibleNullability(
    from = ArrayType(DoubleType, containsNull = false),
    to = ArrayType(DoubleType, containsNull = false),
    expected = true)
  checkEqualsIgnoreCompatibleNullability(
    from = ArrayType(DoubleType, containsNull = false),
    to = ArrayType(DoubleType, containsNull = true),
    expected = true)
  checkEqualsIgnoreCompatibleNullability(
    from = ArrayType(DoubleType, containsNull = true),
    to = ArrayType(DoubleType, containsNull = false),
    expected = false)
  checkEqualsIgnoreCompatibleNullability(
    from = ArrayType(DoubleType, containsNull = false),
    to = ArrayType(StringType, containsNull = false),
    expected = false)

  checkEqualsIgnoreCompatibleNullability(
    from = MapType(StringType, DoubleType, valueContainsNull = true),
    to = MapType(StringType, DoubleType, valueContainsNull = true),
    expected = true)
  checkEqualsIgnoreCompatibleNullability(
    from = MapType(StringType, DoubleType, valueContainsNull = false),
    to = MapType(StringType, DoubleType, valueContainsNull = false),
    expected = true)
  checkEqualsIgnoreCompatibleNullability(
    from = MapType(StringType, DoubleType, valueContainsNull = false),
    to = MapType(StringType, DoubleType, valueContainsNull = true),
    expected = true)
  checkEqualsIgnoreCompatibleNullability(
    from = MapType(StringType, DoubleType, valueContainsNull = true),
    to = MapType(StringType, DoubleType, valueContainsNull = false),
    expected = false)
  checkEqualsIgnoreCompatibleNullability(
    from = MapType(StringType, ArrayType(IntegerType, true), valueContainsNull = true),
    to = MapType(StringType, ArrayType(IntegerType, false), valueContainsNull = true),
    expected = false)
  checkEqualsIgnoreCompatibleNullability(
    from = MapType(StringType, ArrayType(IntegerType, false), valueContainsNull = true),
    to = MapType(StringType, ArrayType(IntegerType, true), valueContainsNull = true),
    expected = true)


  checkEqualsIgnoreCompatibleNullability(
    from = StructType(StructField("a", StringType, nullable = true) :: Nil),
    to = StructType(StructField("a", StringType, nullable = true) :: Nil),
    expected = true)
  checkEqualsIgnoreCompatibleNullability(
    from = StructType(StructField("a", StringType, nullable = false) :: Nil),
    to = StructType(StructField("a", StringType, nullable = false) :: Nil),
    expected = true)
  checkEqualsIgnoreCompatibleNullability(
    from = StructType(StructField("a", StringType, nullable = false) :: Nil),
    to = StructType(StructField("a", StringType, nullable = true) :: Nil),
    expected = true)
  checkEqualsIgnoreCompatibleNullability(
    from = StructType(StructField("a", StringType, nullable = true) :: Nil),
    to = StructType(StructField("a", StringType, nullable = false) :: Nil),
    expected = false)
  checkEqualsIgnoreCompatibleNullability(
    from = StructType(
      StructField("a", StringType, nullable = false) ::
      StructField("b", StringType, nullable = true) :: Nil),
    to = StructType(
      StructField("a", StringType, nullable = false) ::
      StructField("b", StringType, nullable = false) :: Nil),
    expected = false)

  def checkCatalogString(dt: DataType): Unit = {
    test(s"catalogString: $dt") {
      val dt2 = CatalystSqlParser.parseDataType(dt.catalogString)
      assert(dt === dt2)
    }
  }
  def createStruct(n: Int): StructType = new StructType(Array.tabulate(n) {
    i => StructField(s"col$i", IntegerType, nullable = true)
  })

  checkCatalogString(NullType)
  checkCatalogString(BooleanType)
  checkCatalogString(ByteType)
  checkCatalogString(ShortType)
  checkCatalogString(IntegerType)
  checkCatalogString(LongType)
  checkCatalogString(FloatType)
  checkCatalogString(DoubleType)
  checkCatalogString(DecimalType(10, 5))
  checkCatalogString(BinaryType)
  checkCatalogString(StringType)
  checkCatalogString(DateType)
  checkCatalogString(TimestampType)
  checkCatalogString(createStruct(4))
  checkCatalogString(createStruct(40))
  checkCatalogString(ArrayType(IntegerType))
  checkCatalogString(ArrayType(createStruct(40)))
  checkCatalogString(MapType(IntegerType, StringType))
  checkCatalogString(MapType(IntegerType, createStruct(40)))

  def checkEqualsStructurally(
      from: DataType,
      to: DataType,
      expected: Boolean,
      ignoreNullability: Boolean = false): Unit = {
    val testName = s"equalsStructurally: (from: $from, to: $to, " +
      s"ignoreNullability: $ignoreNullability)"
    test(testName) {
      assert(DataType.equalsStructurally(from, to, ignoreNullability) === expected)
    }
  }

  checkEqualsStructurally(BooleanType, BooleanType, true)
  checkEqualsStructurally(IntegerType, IntegerType, true)
  checkEqualsStructurally(IntegerType, LongType, false)
  checkEqualsStructurally(ArrayType(IntegerType, true), ArrayType(IntegerType, true), true)
  checkEqualsStructurally(ArrayType(IntegerType, true), ArrayType(IntegerType, false), false)

  checkEqualsStructurally(
    new StructType().add("f1", IntegerType),
    new StructType().add("f2", IntegerType),
    true)
  checkEqualsStructurally(
    new StructType().add("f1", IntegerType),
    new StructType().add("f2", IntegerType, false),
    false)

  checkEqualsStructurally(
    new StructType().add("f1", IntegerType).add("f", new StructType().add("f2", StringType)),
    new StructType().add("f2", IntegerType).add("g", new StructType().add("f1", StringType)),
    true)
  checkEqualsStructurally(
    new StructType().add("f1", IntegerType).add("f", new StructType().add("f2", StringType, false)),
    new StructType().add("f2", IntegerType).add("g", new StructType().add("f1", StringType)),
    false)
  checkEqualsStructurally(
    new StructType().add("f1", IntegerType).add("f", new StructType().add("f2", StringType, false)),
    new StructType().add("f2", IntegerType).add("g", new StructType().add("f1", StringType)),
    true,
    ignoreNullability = true)
  checkEqualsStructurally(
    new StructType().add("f1", IntegerType).add("f", new StructType().add("f2", StringType)),
    new StructType().add("f2", IntegerType, nullable = false)
      .add("g", new StructType().add("f1", StringType)),
    true,
    ignoreNullability = true)

  checkEqualsStructurally(
    ArrayType(
      ArrayType(IntegerType, true), true),
    ArrayType(
      ArrayType(IntegerType, true), true),
    true,
     ignoreNullability = false)

  checkEqualsStructurally(
    ArrayType(
      ArrayType(IntegerType, true), false),
    ArrayType(
      ArrayType(IntegerType, true), true),
    false,
    ignoreNullability = false)

  checkEqualsStructurally(
    ArrayType(
      ArrayType(IntegerType, true), true),
    ArrayType(
      ArrayType(IntegerType, false), true),
    false,
    ignoreNullability = false)

  checkEqualsStructurally(
    ArrayType(
      ArrayType(IntegerType, true), false),
    ArrayType(
      ArrayType(IntegerType, true), true),
    true,
    ignoreNullability = true)

  checkEqualsStructurally(
    ArrayType(
      ArrayType(IntegerType, true), false),
    ArrayType(
      ArrayType(IntegerType, false), true),
    true,
    ignoreNullability = true)

  checkEqualsStructurally(
    MapType(
      ArrayType(IntegerType, true), ArrayType(IntegerType, true), true),
    MapType(
      ArrayType(IntegerType, true), ArrayType(IntegerType, true), true),
    true,
    ignoreNullability = false)

  checkEqualsStructurally(
    MapType(
      ArrayType(IntegerType, true), ArrayType(IntegerType, true), true),
    MapType(
      ArrayType(IntegerType, true), ArrayType(IntegerType, true), false),
    false,
    ignoreNullability = false)

  checkEqualsStructurally(
    MapType(
      ArrayType(IntegerType, true), ArrayType(IntegerType, true), true),
    MapType(
      ArrayType(IntegerType, true), ArrayType(IntegerType, false), true),
    false,
    ignoreNullability = false)

  checkEqualsStructurally(
    MapType(
      ArrayType(IntegerType, true), ArrayType(IntegerType, true), true),
    MapType(
      ArrayType(IntegerType, true), ArrayType(IntegerType, true), false),
    true,
    ignoreNullability = true)

  checkEqualsStructurally(
    MapType(
      ArrayType(IntegerType, true), ArrayType(IntegerType, true), true),
    MapType(
      ArrayType(IntegerType, true), ArrayType(IntegerType, false), true),
    true,
    ignoreNullability = true)

  checkEqualsStructurally(
    MapType(
      ArrayType(IntegerType, false), ArrayType(IntegerType, true), true),
    MapType(
      ArrayType(IntegerType, true), ArrayType(IntegerType, true), true),
    true,
    ignoreNullability = true)

  def checkEqualsStructurallyByName(
      from: DataType,
      to: DataType,
      expected: Boolean,
      caseSensitive: Boolean = false): Unit = {
    val testName = s"SPARK-36918: equalsStructurallyByName: (from: $from, to: $to, " +
        s"caseSensitive: $caseSensitive)"

    val resolver = if (caseSensitive) {
      caseSensitiveResolution
    } else {
      caseInsensitiveResolution
    }

    test(testName) {
      assert(DataType.equalsStructurallyByName(from, to, resolver) === expected)
    }
  }

  checkEqualsStructurallyByName(
    ArrayType(
      ArrayType(IntegerType)),
    ArrayType(
      ArrayType(IntegerType)),
    true)

  // Type doesn't matter
  checkEqualsStructurallyByName(BooleanType, BooleanType, true)
  checkEqualsStructurallyByName(BooleanType, IntegerType, true)
  checkEqualsStructurallyByName(IntegerType, LongType, true)

  checkEqualsStructurallyByName(
    new StructType().add("f1", IntegerType).add("f2", IntegerType),
    new StructType().add("f1", LongType).add("f2", StringType),
    true)

  checkEqualsStructurallyByName(
    new StructType().add("f1", IntegerType).add("f2", IntegerType),
    new StructType().add("f2", LongType).add("f1", StringType),
    false)

  checkEqualsStructurallyByName(
    new StructType().add("f1", IntegerType).add("f", new StructType().add("f2", StringType)),
    new StructType().add("f1", LongType).add("f", new StructType().add("f2", BooleanType)),
    true)

  checkEqualsStructurallyByName(
    new StructType().add("f1", IntegerType).add("f", new StructType().add("f2", StringType)),
    new StructType().add("f", new StructType().add("f2", BooleanType)).add("f1", LongType),
    false)

  checkEqualsStructurallyByName(
    new StructType().add("f1", IntegerType).add("f2", IntegerType),
    new StructType().add("F1", LongType).add("F2", StringType),
    true,
    caseSensitive = false)

  checkEqualsStructurallyByName(
    new StructType().add("f1", IntegerType).add("f2", IntegerType),
    new StructType().add("F1", LongType).add("F2", StringType),
    false,
    caseSensitive = true)

  test("SPARK-25031: MapType should produce current formatted string for complex types") {
    val keyType: DataType = StructType(Seq(
      StructField("a", DataTypes.IntegerType),
      StructField("b", DataTypes.IntegerType)))

    val valueType: DataType = StructType(Seq(
      StructField("c", DataTypes.IntegerType),
      StructField("d", DataTypes.IntegerType)))

    val stringConcat = new StringConcat

    MapType(keyType, valueType).buildFormattedString(prefix = "", stringConcat = stringConcat)

    val result = stringConcat.toString()
    val expected =
      """-- key: struct
        |    |-- a: integer (nullable = true)
        |    |-- b: integer (nullable = true)
        |-- value: struct (valueContainsNull = true)
        |    |-- c: integer (nullable = true)
        |    |-- d: integer (nullable = true)
        |""".stripMargin

    assert(result === expected)
  }
}
