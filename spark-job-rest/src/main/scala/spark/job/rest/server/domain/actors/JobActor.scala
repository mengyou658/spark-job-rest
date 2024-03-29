package spark.job.rest.server.domain.actors

import akka.actor.{Actor, ActorRef, ActorSelection}
import akka.pattern.ask
import com.typesafe.config.Config
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.LoggerFactory
import spark.job.rest.api.types.ID
import spark.job.rest.config.durations.AskTimeout
import spark.job.rest.persistence.services.JobPersistenceService
import spark.job.rest.persistence.slickWrapper.Driver.api._
import spark.job.rest.server.domain.actors.ContextManagerActor.{GetContext, NoSuchContext}
import spark.job.rest.server.domain.actors.JobActor._
import spark.job.rest.utils.DatabaseUtils

import scala.concurrent.ExecutionContext.Implicits.global


object JobActor {
  case class JobStatusEnquiry(contextName: String, jobId: String)
  case class RunJob(runningClass: String, contextName: String, config: Config, id: ID)
  case object GetAllJobsStatus

  trait JobStatus
  case class JobRunError(errorMessage: String) extends JobStatus
  case class JobRunSuccess(result:String) extends JobStatus
  case class JobStarted(startTime: Long = new DateTime  (DateTimeZone.UTC).getMillis) extends JobStatus
  case object JobDoesNotExist extends JobStatus
  case object JobAccepted extends JobStatus
}


/**
 * Job actor responsible for job lifecycle.
 * @param config job config
 * @param contextManagerActor context manager
 */
class JobActor(val config: Config, contextManagerActor: ActorRef, connectionProviderActor: ActorRef)
  extends Actor with JobPersistenceService with DatabaseUtils with AskTimeout {

  val log = LoggerFactory.getLogger(getClass)

  /**
   * Database connection received from connection provider [[DatabaseServerActor]]
   */
  var db: Database = _

  override def preStart() = {
    db = dbConnection(connectionProviderActor)
  }

  override def receive: Receive = {
    case job@RunJob(runningClass, contextName, _, jobId) =>
      log.info(s"Received RunJob message : runningClass=$runningClass context=$contextName uuid=$jobId")

      val fromWebApi = sender()
      val getContextFuture = contextManagerActor ? GetContext(contextName)

      getContextFuture onSuccess {
        case contextRef: ActorSelection =>
          log.info(s"Sending RunJob message to actor $contextRef")
          contextRef ! job
          // Report to client
          fromWebApi ! JobAccepted
        case NoSuchContext =>
          persistJobFailure(jobId, s"No such context $contextName", db)
          fromWebApi ! NoSuchContext
        case e@_ => log.warn(s"Received UNKNOWN TYPE when asked for context. Type received $e")
      }

      getContextFuture onFailure {
        case e =>
          fromWebApi ! e
          persistJobFailure(jobId, s"Unrecoverable error during submit: ${e.getStackTrace}", db)
          log.error(s"An error has occurred.", e)
      }
  }
}


