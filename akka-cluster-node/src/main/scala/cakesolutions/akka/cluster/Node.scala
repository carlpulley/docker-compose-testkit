package cakesolutions.akka.cluster

import akka.actor.ActorSystem
import akka.cluster.Cluster

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

object Node extends App {

  implicit val system = ActorSystem("SBRTestCluster")

  system.log.info("Node started")

  val cluster = Cluster(system)

  cluster.registerOnMemberUp {
    system.log.info("Successfully joined the cluster")
  }

  cluster.registerOnMemberRemoved {
    system.log.error("Member removed from cluster - shutting down!")

    system.registerOnTermination(System.exit(0))
    system.terminate()

    new Thread {
      override def run(): Unit = {
        if (Try(Await.ready(system.whenTerminated, 10.seconds)).isFailure) {
          System.exit(-1)
        }
      }
    }.start()
  }

}
