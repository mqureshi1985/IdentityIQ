package com.iam;

import org.apache.log4j.Logger;

public class IAMLogger {
  public static final String SAILPOINT = "sailpoint";

  public static final String JOINER = "joiner";
  public static final String MOVER = "mover";
  public static final String LEAVER = "leaver";
  public static final String ATTRSYNC = "attrsync";
  private static final Logger joinerLogger = Logger.getLogger("rule.iam.joiner");
  private static final Logger moverLogger = Logger.getLogger("rule.iam.mover");
  private static final Logger leaverLogger = Logger.getLogger("rule.iam.leaver");
  private static final Logger attrsyncLogger = Logger.getLogger("rule.iam.attrsync");
  private static final Logger sailpointLogger = Logger.getLogger("rule.iam.sp");

  private static IAMLogger iamLogger;

  public static IAMLogger getInstance() {
    if (iamLogger == null) {
      synchronized (IAMLogger.class) {
        IAMLogger inst = iamLogger;
        if (inst == null) {
          synchronized (IAMLogger.class) {
            iamLogger = new IAMLogger();
          }
        }
      }
    }
    return iamLogger;
  }

  public static void info(String loggerName, String log) {
    getInstance().getLogger(loggerName).info(log);
  }

  public static void debug(String loggerName, String log) {
    getInstance().getLogger(loggerName).debug(log);
  }

  public static void trace(String loggerName, String log) {
    getInstance().getLogger(loggerName).trace(log);
  }

  public static void error(String loggerName, String log) {
    getInstance().getLogger(loggerName).error(log);
  }

  public static void error(String loggerName, String log, Exception ex) {
    String errorMsg = "";
    if (ex instanceof NullPointerException) {
      try {
        NullPointerException n = (NullPointerException) ex;
        StackTraceElement stackTrace = n.getStackTrace()[0];
        errorMsg = ex.getMessage() + "Exception at Class:" + stackTrace.getClassName() + " method:" + stackTrace.getMethodName() + " line:" + stackTrace.getLineNumber();
      } catch (Exception ex2) {
        errorMsg = ex.getMessage() + " Unable to findError";
        ex2.printStackTrace();
      }
    } else {
      errorMsg = ex.toString();
    }
    getInstance().getLogger(loggerName).error("ERROR |" + log + " " + errorMsg);
  }

  public Logger getLogger(String loggerName) {
    switch (loggerName) {
    case JOINER:
      return joinerLogger;
    case MOVER:
      return moverLogger;
    case LEAVER:
      return leaverLogger;
    case ATTRSYNC:
      return attrsyncLogger;
    default:
      return sailpointLogger;
    }
  }
}