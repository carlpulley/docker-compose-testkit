package cakesolutions.docker.testkit.logging

import akka.event.Logging._

trait ConsoleLogger {
  implicit val log: Logger = new Logger {
    override def debug(message: String): Unit = {
      if (levelFor(config.getString("akka.loglevel")).exists(_ >= DebugLevel)) {
        println(s"DEBUG: $message")
      }
    }

    override def warn(message: String): Unit = {
      if (levelFor(config.getString("akka.loglevel")).exists(_ >= WarningLevel)) {
        println(s"WARN: $message")
      }
    }

    override def error(message: String, reason: Throwable): Unit = {
      if (levelFor(config.getString("akka.loglevel")).exists(_ >= ErrorLevel)) {
        if (reason == null) {
          println(s"ERROR: $message")
        } else {
          println(s"ERROR: $message - reason: $reason")
        }
      }
    }

    override def info(message: String): Unit = {
      if (levelFor(config.getString("akka.loglevel")).exists(_ >= InfoLevel)) {
        println(s"INFO: $message")
      }
    }
  }
}
