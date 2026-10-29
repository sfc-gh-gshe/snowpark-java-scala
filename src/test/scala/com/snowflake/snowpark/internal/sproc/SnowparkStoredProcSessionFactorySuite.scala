package com.snowflake.snowpark.internal.sproc

import java.sql.Connection

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

import com.snowflake.snowpark.Session
import com.snowflake.snowpark.internal.Utils.TempObjectType
import com.snowflake.sproc.api.StoredProcRuntimeLoader
import net.snowflake.client.jdbc.SnowflakeConnectionV1
import org.mockito.Mockito
import org.scalatest.funsuite.AnyFunSuite

class SnowparkStoredProcSessionFactorySuite extends AnyFunSuite {

  test("runtime loader finds the Snowpark session factory") {
    val factory = StoredProcRuntimeLoader.loadSessionFactory(getClass.getClassLoader)
    assert(factory.isInstanceOf[SnowparkStoredProcSessionFactory])
  }

  test("createSession rejects a JDBC 3.x connection") {
    val ex = intercept[IllegalArgumentException](
      new SnowparkStoredProcSessionFactory()
        .createSession(Mockito.mock(classOf[SnowflakeConnectionV1])))
    assert(ex.getMessage.contains("cannot be unwrapped"))
  }

  test("session forwards the contract to the Snowpark session") {
    val session = Mockito.mock(classOf[Session])
    val conn = Mockito.mock(classOf[Connection])
    Mockito.when(session.jdbcConnection).thenReturn(conn)
    Mockito
      .when(session.getTempObjectMap)
      .thenReturn(
        TrieMap[String, TempObjectType](
          "DB.S.T" -> TempObjectType.Table,
          "DB.S.F" -> TempObjectType.FileFormat))
    val sprocSession = new SnowparkStoredProcSession(session)

    sprocSession.addDependency("@stage/a.jar")
    Mockito.verify(session).addDependency("@stage/a.jar")
    sprocSession.addPackage("com.example:lib:1.0")
    Mockito.verify(session).addPackage("com.example:lib:1.0")

    assert(sprocSession.handlerSession(false) eq session)
    val javaSession = sprocSession.handlerSession(true)
    assert(javaSession.isInstanceOf[com.snowflake.snowpark_java.Session])
    assert(sprocSession.handlerSession(true) eq javaSession)

    assert(sprocSession.connection() eq conn)
    val dropKeywords = sprocSession.tempObjects().asScala.map { case (k, v) => k -> v.toString }
    assert(dropKeywords == Map("DB.S.T" -> "Table", "DB.S.F" -> "File Format"))
  }
}
