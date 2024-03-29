package spark.job.rest.server

import akka.actor.{ActorSystem, Props}
import org.slf4j.LoggerFactory
import spark.job.rest.config.defaultApplicationConfig
import spark.job.rest.logging.LoggingOutputStream
import spark.job.rest.server.domain.actors.ContextActor
import spark.job.rest.utils.ActorUtils

/**
 * Spark context container entry point.
 */
object MainContext {

  LoggingOutputStream.redirectConsoleOutput()
  val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]) {
    val contextName = System.getenv("CONTEXT_NAME")
    val port = System.getenv("CONTEXT_PORT").toInt

    log.info(s"Started new process for contextName = $contextName with port = $port")

    // Use default config as a base
    val defaultConfig = defaultApplicationConfig
    val config = ActorUtils.remoteConfig("localhost", port, defaultConfig)
    val system = ActorSystem(ActorUtils.PREFIX_CONTEXT_SYSTEM + contextName, config)

    system.actorOf(Props(new ContextActor(defaultConfig)), ActorUtils.PREFIX_CONTEXT_ACTOR + contextName)

    log.info(s"Initialized system ${ActorUtils.PREFIX_CONTEXT_SYSTEM}$contextName and actor ${ActorUtils.PREFIX_CONTEXT_SYSTEM}$contextName")
  }
}
