package cakesolutions.docker.testkit.logging

import scala.sys.process.ProcessLogger

trait Logger {
  def debug(message: String): Unit

  def info(message: String): Unit

  def warn(message: String): Unit

  def error(message: String, reason: Throwable = null): Unit

  def stderr: ProcessLogger = ProcessLogger { err =>
    if (err.length > 0 && err.charAt(0) != 27.toChar) {
      debug(err)
    }
  }

  def devNull: ProcessLogger = ProcessLogger(_ => {})
}
