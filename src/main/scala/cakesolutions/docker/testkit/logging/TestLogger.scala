package cakesolutions.docker.testkit.logging

import akka.event.Logging
import org.scalatest.{Alerting, Notifying}

trait TestLogger {
  self: Notifying with Alerting =>

  // TODO: make configurable based on log level
  implicit val log: Logger = new Logger {
    override def debug(message: String): Unit = {
      self.note(s"DEBUG: $message")
    }

    override def warn(message: String): Unit = {
      self.note(s"WARN: $message")
    }

    override def error(message: String, reason: Throwable): Unit = {
      if (reason == null) {
        self.alert(s"ERROR: $message")
      } else {
        self.alert(s"ERROR: $message - reason: $reason\n${exceptionStr(reason)}")
      }
    }

    override def info(message: String): Unit = {
      self.note(message)
    }

    private def exceptionStr(exn: Throwable): String = {
      Logging.stackTraceFor(exn)
    }
  }
}
