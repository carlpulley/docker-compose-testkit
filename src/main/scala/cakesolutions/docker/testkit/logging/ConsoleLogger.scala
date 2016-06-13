package cakesolutions.docker.testkit.logging

trait ConsoleLogger {
  // TODO: make configurable based on log level
  implicit val log: Logger = new Logger {
    override def debug(message: String): Unit = {
      println(s"DEBUG: $message")
    }

    override def warn(message: String): Unit = {
      println(s"WARN: $message")
    }

    override def error(message: String, reason: Throwable): Unit = {
      if (reason == null) {
        println(s"ERROR: $message")
      } else {
        println(s"ERROR: $message - reason: $reason")
      }
    }

    override def info(message: String): Unit = {
      println(s"INFO: $message")
    }
  }
}
