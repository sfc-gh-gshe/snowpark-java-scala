package com.snowflake.snowpark.internal.sproc

import com.snowflake.snowpark.Session
import net.snowflake.client.api.connection.SnowflakeConnection
import org.mockito.Mockito
import org.scalatest.funsuite.AnyFunSuite

import scala.io.Source
import scala.util.Try

/**
 * Offline unit tests for [[StoredProcSessionFactory]].
 *
 * These tests do NOT require a live Snowflake connection. The happy-path test stubs just enough
 * JDBC interface methods so that Session construction completes without network I/O.
 *
 * Design assertions:
 *   - [[StoredProcSessionFactory]] must not reference any concrete JDBC class ({@code
 *     SnowflakeConnectionImpl}, {@code SnowflakeConnectionV1} , {@code SFBaseSession} ).
 *   - The sproc seam depends only on [[java.sql.Connection]] and
 *     [[net.snowflake.client.api.connection.SnowflakeConnection]].
 *   - The unwrap contract is enforced: a connection that cannot be unwrapped to
 *     [[SnowflakeConnection]] is rejected before Session construction.
 */
class StoredProcSessionFactorySuite extends AnyFunSuite {

  // -------------------------------------------------------------------------
  // Null / precondition checks
  // -------------------------------------------------------------------------

  test("fromJdbcConnection(null) throws IllegalArgumentException") {
    val ex = intercept[IllegalArgumentException] {
      StoredProcSessionFactory.fromJdbcConnection(null)
    }
    assert(ex.getMessage.contains("conn must not be null"))
  }

  // -------------------------------------------------------------------------
  // Unwrap contract: reject connections not wrapping SnowflakeConnection
  // -------------------------------------------------------------------------

  test(
    "fromJdbcConnection(conn not wrapping SnowflakeConnection) throws IllegalArgumentException") {
    val wrongConn = Mockito.mock(classOf[java.sql.Connection])
    // isWrapperFor returns false → factory should reject before reaching Session
    Mockito.when(wrongConn.isWrapperFor(classOf[SnowflakeConnection])).thenReturn(false)
    val ex = intercept[IllegalArgumentException] {
      StoredProcSessionFactory.fromJdbcConnection(wrongConn)
    }
    assert(
      ex.getMessage.contains("cannot be unwrapped to"),
      s"Expected 'cannot be unwrapped to' in: ${ex.getMessage}")
    assert(
      ex.getMessage.contains("SnowflakeConnection"),
      s"Expected 'SnowflakeConnection' in: ${ex.getMessage}")
    // Must NOT mention any concrete implementation class
    assert(
      !ex.getMessage.contains("SnowflakeConnectionImpl"),
      s"Error message must not name a concrete class: ${ex.getMessage}")
  }

  // -------------------------------------------------------------------------
  // Happy path: valid SnowflakeConnection wrapper → non-null Session
  // -------------------------------------------------------------------------

  /**
   * Stubs a [[java.sql.Connection]] that wraps a mock [[SnowflakeConnection]].
   *
   * Mockito default behaviour for mocked interface methods:
   *   - methods returning Object → null
   *   - methods returning void → no-op
   *   - methods returning boolean → false
   *
   * Only `isClosed` needs explicit stubbing (must be false to pass `withValidConnection`).
   * `getSessionParameter` (String) and `submitTelemetry` (void) need no stubbing since their
   * Mockito defaults (null, no-op) are exactly what an offline stub requires.
   */
  test("fromJdbcConnection(wrapping SnowflakeConnection) returns non-null Session (offline stub)") {
    val mockSfConn = Mockito.mock(classOf[SnowflakeConnection])
    val mockConn = Mockito.mock(classOf[java.sql.Connection])

    // Boundary unwrap — called once in ServerConnection constructor
    Mockito.when(mockConn.isWrapperFor(classOf[SnowflakeConnection])).thenReturn(true)
    Mockito.when(mockConn.unwrap(classOf[SnowflakeConnection])).thenReturn(mockSfConn)
    // isClosed — checked in withValidConnection for every operation
    Mockito.when(mockConn.isClosed).thenReturn(false)
    // getSessionParameter → default null, submitTelemetry → default no-op: no stubbing needed

    val session: Session = StoredProcSessionFactory.fromJdbcConnection(mockConn)
    assert(session != null, "fromJdbcConnection must return a non-null Session")
  }

  // -------------------------------------------------------------------------
  // Source-level purity: no concrete JDBC class in the sproc factory
  // -------------------------------------------------------------------------

  /**
   * Strip Scaladoc / block-comment / line-comment lines so purity checks only inspect actual import
   * and code statements. Class names that appear inside documentation strings are intentional and
   * are not violations of the contract.
   */
  private def codeOnlyLines(src: String): String =
    src
      .split("\n")
      .filterNot { line =>
        val t = line.trim
        t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
      }
      .mkString("\n")

  test("StoredProcSessionFactory must not import or use concrete JDBC implementation classes") {
    val path =
      "src/main/scala/com/snowflake/snowpark/internal/sproc/StoredProcSessionFactory.scala"
    val srcOpt = Try(Source.fromFile(path).mkString).toOption
    assume(srcOpt.isDefined, s"Source file not found (running from unexpected CWD): $path")
    val codeLines = codeOnlyLines(srcOpt.get)
    Seq("SnowflakeConnectionImpl", "SnowflakeConnectionV1", "SFBaseSession").foreach { badClass =>
      assert(
        !codeLines.contains(badClass),
        s"StoredProcSessionFactory must not import/use $badClass in non-comment code")
    }
  }

  test("Session.scala must not import or use concrete JDBC implementation classes") {
    val path = "src/main/scala/com/snowflake/snowpark/Session.scala"
    val srcOpt = Try(Source.fromFile(path).mkString).toOption
    assume(srcOpt.isDefined, s"Source file not found: $path")
    val codeLines = codeOnlyLines(srcOpt.get)
    Seq("SnowflakeConnectionImpl", "SnowflakeConnectionV1").foreach { badClass =>
      assert(
        !codeLines.contains(badClass),
        s"Session.scala must not import/use $badClass in non-comment code")
    }
  }
}
