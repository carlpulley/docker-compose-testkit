package cakesolutions.docker.testkit.clients

import cakesolutions.docker.testkit.DockerImage
import monix.execution.Scheduler

object LibFiuClient {
  object libc {
    def mm(name: String = "*") = s"libc/mm/$name"
    def str(name: String = "*") = s"libc/str/$name"
  }

  object linux {
    def io(name: String = "*") = s"linux/io/$name"
  }

  object posix {
    def io(name: String = "*") = s"posix/io/$name"
    def mm(name: String = "*") = s"posix/mm/$name"
    def proc(name: String = "*") = s"posix/proc/$name"
  }

  implicit class LibFiuUtil(image: DockerImage) {
    private[this] val libfiu = "/usr/local/bin/fiu-ctrl"

    def enable(path: String)(implicit scheduler: Scheduler): Unit = {
      image
        .exec(libfiu, "-c", s"enable name=$path", "1")
        .subscribe()
    }

    def disable(path: String)(implicit scheduler: Scheduler): Unit = {
      image
        .exec(libfiu, "-c", s"disable name=$path", "1")
        .subscribe()
    }

    def random(path: String, probability: Double)(implicit scheduler: Scheduler): Unit = {
      require(0 <= probability && probability <= 1)

      image
        .exec(libfiu, "-c", f"enable_random name=$path,probability=$probability%1.3f", "1")
        .subscribe()
    }
  }
}
