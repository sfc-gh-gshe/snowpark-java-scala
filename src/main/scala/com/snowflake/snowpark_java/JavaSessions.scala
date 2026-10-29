package com.snowflake.snowpark_java

/**
 * Creates Java API sessions from internal Scala code. The Java [[Session]] constructor is
 * package-private, so this must live in the same package.
 */
private[snowflake] object JavaSessions {
  def wrap(session: com.snowflake.snowpark.Session): Session = new Session(session)
}
