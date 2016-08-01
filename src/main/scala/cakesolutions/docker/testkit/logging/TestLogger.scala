package cakesolutions.docker.testkit.logging

import akka.event.Logging
import org.scalatest.{Alerting, Informing, Notifying}

trait TestLogger {
  self: Informing with Notifying with Alerting =>

  // TODO: make configurable based on log level
  implicit val log: Logger = new Logger {
    override def debug(message: String): Unit = {
      self.note(message)
    }

    override def warn(message: String): Unit = {
      self.note(message)
    }

    override def error(message: String, reason: Throwable): Unit = {
      if (reason == null) {
        self.alert(s"ERROR: $message")
      } else {
        self.alert(s"ERROR: $message - reason: $reason\n${exceptionStr(reason)}")
      }
    }

    override def info(message: String): Unit = {
      self.info(message)
    }

    private def exceptionStr(exn: Throwable): String = {
      Logging.stackTraceFor(exn)
    }
  }
}
