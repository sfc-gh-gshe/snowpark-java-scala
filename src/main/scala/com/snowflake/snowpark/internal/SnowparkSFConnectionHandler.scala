package com.snowflake.snowpark.internal

// CLIENT PATH ONLY — not reachable from the stored-procedure JNI seam.
// This file's only purpose is to inject the Snowpark app-id (LoginInfoDTO.SF_SNOWPARK_APP_ID)
// during normal (non-stored-procedure) connection creation.  The concrete
// SnowflakeConnectionImpl is constructed in ServerConnection.createClientConnection() using
// this handler, then immediately exposed as java.sql.Connection + SnowflakeConnection so that
// no other Snowpark code references the implementation class.
import com.snowflake.snowpark.internal.SnowparkSFConnectionHandler.extractValidVersionNumber
import net.snowflake.client.jdbc.internal.snowflake.common.core.LoginInfoDTO // CLIENT PATH ONLY
import net.snowflake.client.internal.jdbc.{
  DefaultSFConnectionHandler,
  SnowflakeConnectString
} // CLIENT PATH ONLY

import java.util.Properties

object SnowparkSFConnectionHandler {
  // Version format copied from GS ClientVersionUtils.java
  private val VERSION_FORMAT_PATTERN = "[0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?".r

  def extractValidVersionNumber(version: String): String = {
    VERSION_FORMAT_PATTERN
      .findFirstIn(version)
      .getOrElse(throw ErrorMessage.MISC_INVALID_CLIENT_VERSION(version))
  }
}

class SnowparkSFConnectionHandler(conStr: SnowflakeConnectString)
    extends DefaultSFConnectionHandler(conStr) {

  override def initializeConnection(url: String, info: Properties): Unit = {
    val connStr = SnowflakeConnectString.parse(url, info)
    super.initialize(
      connStr,
      LoginInfoDTO.SF_SNOWPARK_APP_ID,
      extractValidVersionNumber(Utils.Version))
  }
}
