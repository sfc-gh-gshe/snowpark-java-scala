package com.snowflake.snowpark.internal.sproc

import java.sql.Connection
import java.util.{Map => JMap}

import scala.collection.JavaConverters._

import com.snowflake.snowpark.Session
import com.snowflake.snowpark_java.JavaSessions
import com.snowflake.sproc.api.{StoredProcSession, StoredProcSessionFactory => ApiSessionFactory}

/**
 * Snowpark implementation of the stored-procedure runtime contract, found by the native runtime
 * through `META-INF/services/com.snowflake.sproc.api.StoredProcSessionFactory`.
 *
 * ServiceLoader instantiates it, so it must stay a public class with a no-argument constructor.
 */
class SnowparkStoredProcSessionFactory extends ApiSessionFactory {
  override def createSession(connection: Connection): StoredProcSession =
    new SnowparkStoredProcSession(StoredProcSessionFactory.fromJdbcConnection(connection))
}

private[sproc] class SnowparkStoredProcSession(session: Session) extends StoredProcSession {
  private lazy val javaSession = JavaSessions.wrap(session)

  override def addDependency(path: String): Unit = session.addDependency(path)

  override def addPackage(packageName: String): Unit = session.addPackage(packageName)

  override def handlerSession(javaApi: Boolean): AnyRef =
    if (javaApi) javaSession else session

  override def connection(): Connection = session.jdbcConnection

  override def tempObjects(): JMap[String, AnyRef] =
    new java.util.HashMap[String, AnyRef](session.getTempObjectMap.toMap[String, AnyRef].asJava)
}
