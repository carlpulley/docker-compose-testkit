// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.logging

import akka.event.Logging
import akka.event.Logging._
import org.scalatest.{Alerting, Notifying}

trait TestLogger {
  self: Notifying with Alerting =>

  implicit val log: Logger = new Logger {
    override def debug(message: String): Unit = {
      if (levelFor(config.getString("akka.loglevel")).exists(_ >= DebugLevel)) {
        self.note(s"DEBUG: $message")
      }
    }

    override def warn(message: String): Unit = {
      if (levelFor(config.getString("akka.loglevel")).exists(_ >= WarningLevel)) {
        self.note(s"WARN: $message")
      }
    }

    override def error(message: String, reason: Throwable): Unit = {
      if (levelFor(config.getString("akka.loglevel")).exists(_ >= ErrorLevel)) {
        if (reason == null) {
          self.alert(s"ERROR: $message")
        } else {
          self.alert(s"ERROR: $message - reason: $reason\n${exceptionStr(reason)}")
        }
      }
    }

    override def info(message: String): Unit = {
      if (levelFor(config.getString("akka.loglevel")).exists(_ >= InfoLevel)) {
        self.note(message)
      }
    }

    private def exceptionStr(exn: Throwable): String = {
      Logging.stackTraceFor(exn)
    }
  }
}
