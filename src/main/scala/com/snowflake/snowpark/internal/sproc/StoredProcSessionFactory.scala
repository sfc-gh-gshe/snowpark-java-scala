package com.snowflake.snowpark.internal.sproc

import com.snowflake.snowpark.Session
import net.snowflake.client.api.connection.SnowflakeConnection

/**
 * <b>JDBC 4.x POC / INTERNAL-ONLY – unstable API, subject to removal without notice.</b>
 *
 * <p>Minimal stored-procedure session factory whose public entry point accepts
 * [[java.sql.Connection]] and depends only on the stable public interfaces [[java.sql.Connection]]
 * and [[net.snowflake.client.api.connection.SnowflakeConnection]]. The JNI descriptor is therefore
 * stable across JDBC major versions: {{{(Ljava/sql/Connection;)Lcom/snowflake/snowpark/Session;}}}
 *
 * <h3>Interface contract</h3> <ul> <li>This object must NOT import or reference
 * {@code SnowflakeConnectionImpl} , {@code SnowflakeConnectionV1} , or {@code SFBaseSession} .</li>
 * <li>The Snowflake-specific interface is obtained via
 * {@code conn.unwrap(classOf[SnowflakeConnection])} inside [[ServerConnection]]; the
 * [[SnowflakeConnection]] result is stored as <em>sfConn</em> and used for all Snowflake-specific
 * operations (session parameters, query status, telemetry, stream upload/download).</li> <li>The
 * raw [[java.sql.Connection]] is retained as <em>connection</em> for standard JDBC operations
 * (prepareStatement, createStatement, isClosed, close).</li> </ul>
 *
 * <h3>API boundary</h3> <p>The public method [[fromJdbcConnection]] accepts only
 * [[java.sql.Connection]] so that the JNI descriptor visible to {@code JavaMethodExecutor.cpp} does
 * not reference any JDBC class. The connection must be created via
 * [[net.snowflake.client.internal.jdbc.sproc.StoredProcConnectionFactory.fromHandler]].
 *
 * @since 1.22.0-SNAPSHOT
 *   (POC, JDBC 4.x artifact)
 */
private[snowpark] object StoredProcSessionFactory {

  /**
   * Creates a Snowpark [[Session]] from a [[java.sql.Connection]] produced by the JDBC 4.x
   * stored-procedure connection factory.
   *
   * <p>The JNI descriptor is <code>(Ljava/sql/Connection;)Lcom/snowflake/snowpark/Session;</code>,
   * which does not expose any concrete JDBC type to the native layer.
   *
   * <p>The [[net.snowflake.client.api.connection.SnowflakeConnection]] interface is unwrapped once
   * inside [[ServerConnection]] via {@code conn.unwrap(classOf[SnowflakeConnection])} . No concrete
   * JDBC implementation class ({@code SnowflakeConnectionImpl}, {@code SnowflakeConnectionV1} ) is
   * referenced in this factory or in the stored-procedure connection path.
   *
   * @param conn
   *   Non-null [[java.sql.Connection]] from
   *   [[net.snowflake.client.internal.jdbc.sproc.StoredProcConnectionFactory.fromHandler]].
   * @return
   *   A fully initialised stored-procedure [[Session]].
   * @throws IllegalArgumentException
   *   if {@code conn} is null or cannot be unwrapped to
   *   [[net.snowflake.client.api.connection.SnowflakeConnection]].
   */
  def fromJdbcConnection(conn: java.sql.Connection): Session = {
    require(conn != null, "conn must not be null")
    if (!conn.isWrapperFor(classOf[SnowflakeConnection])) {
      throw new IllegalArgumentException(
        s"[POC JDBC4] StoredProcSessionFactory.fromJdbcConnection: " +
          s"conn (${conn.getClass.getName}) cannot be unwrapped to " +
          s"net.snowflake.client.api.connection.SnowflakeConnection. " +
          s"Ensure the connection was created by StoredProcConnectionFactory.fromHandler.")
    }
    Session(conn)
  }
}
