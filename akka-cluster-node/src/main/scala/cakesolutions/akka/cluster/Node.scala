package cakesolutions.akka.cluster

import java.lang.management.ManagementFactory

import akka.actor.ActorSystem
import akka.cluster.Cluster

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

object Node extends App {

  implicit val system = ActorSystem("SBRTestCluster")

  system.log.info("Node started")
  sys.env.toList.sortBy(_._1).foreach {
    case (key, value) =>
      system.log.info(s"environment: $key=$value")
  }
  private val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
  private val nonHeap = ManagementFactory.getMemoryMXBean.getNonHeapMemoryUsage
  system.log.info(s"java.lang.memory.heap: committed=${humanReadable(heap.getCommitted)}")
  system.log.info(s"java.lang.memory.heap: initial=${humanReadable(heap.getInit)}")
  system.log.info(s"java.lang.memory.heap: maximum=${humanReadable(heap.getMax)}")
  system.log.info(s"java.lang.memory.heap: used=${humanReadable(heap.getUsed)}")
  system.log.info(s"java.lang.memory.non-heap: committed=${humanReadable(nonHeap.getCommitted)}")
  system.log.info(s"java.lang.memory.non-heap: initial=${humanReadable(nonHeap.getInit)}")
  system.log.info(s"java.lang.memory.non-heap: maximum=${humanReadable(nonHeap.getMax)}")
  system.log.info(s"java.lang.memory.non-heap: used=${humanReadable(nonHeap.getUsed)}")
  system.log.info(s"runtime: available-processors=${Runtime.getRuntime.availableProcessors()}")

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

  private def humanReadable(bytes: Long): String = {
    val unit = 1000
    if (bytes < 0) {
      "undefined"
    } else if (bytes < unit) {
      s"${bytes}B"
    } else {
      val exp = (Math.log(bytes) / Math.log(unit)).toInt
      val pre = "kMGTPE".charAt(exp - 1)
      f"${bytes / Math.pow(unit, exp)}%.1f${pre}B"
    }
  }

}
