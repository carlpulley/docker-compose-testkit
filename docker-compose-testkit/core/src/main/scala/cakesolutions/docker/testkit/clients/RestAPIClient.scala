// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.clients

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import cakesolutions.docker.testkit.logging.Logger

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object RestAPIClient {
  def restClient(implicit system: ActorSystem, materializer: ActorMaterializer, ec: ExecutionContext, log: Logger): Route = { ctx =>
    val httpRequest = ctx.request
    val response =
      Http()
        .singleRequest(httpRequest)
        .map(RouteResult.Complete)

    response.onComplete {
      case Success(http) =>
        log.info(s"Successful HTTP query:\n      ${trimDisplay(httpRequest.toString)}\n      ${trimDisplay(http.response.toString)}")

      case Failure(exn) =>
        log.error(s"Failed to receive response to ${trimDisplay(httpRequest.toString)}", exn)
    }

    response
  }

  private def trimDisplay(data: String): String = {
    val displayWidth = 250
    val display = data.replaceAllLiterally("\n", "")

    if (display.length < displayWidth) {
      display
    } else {
      display.take(displayWidth) + " ..."
    }
  }
}
