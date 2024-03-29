package spark.job.rest.persistence

import org.junit.runner.RunWith
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, MustMatchers, WordSpec}
import spark.job.rest.api.entities.ContextState._
import spark.job.rest.persistence.schema._
import spark.job.rest.persistence.services.ContextPersistenceService
import spark.job.rest.persistence.slickWrapper.Driver.api._
import spark.job.rest.test.durations.{dbTimeout, timeLimits}
import spark.job.rest.test.fixtures
import spark.job.rest.utils.schemaUtils.setupDatabaseSchema

import scala.concurrent.Await

/**
 * Test suit for database schema: [[schema]]
 */
@RunWith(classOf[JUnitRunner])
class ContextPersistenceServiceSpec
  extends WordSpec
  with MustMatchers with BeforeAndAfter with TimeLimitedTests with ContextPersistenceService {

  val timeLimit = timeLimits.dbTest

  val config = fixtures.applicationConfig

  implicit val timeout = dbTimeout

  val server = new DatabaseServer(fixtures.applicationConfig)
  server.reset()
  def db = server.db

  before {
    server.start()
    setupDatabaseSchema(server.db, resetSchema = true)
  }

  after {
    server.reset()
  }

  "ContextPersistenceService" should {
    "update context state" in {
      val (_, finalContext) = createAndUpdateThrough(Requested, Running, "awesome jump")
      finalContext.state mustEqual Running
      finalContext.details mustEqual "awesome jump"
    }

    "not change context state if it is failed" in {
      val (_, finalContext) = createAndUpdateThrough(Failed, Running)
      finalContext.state mustEqual Failed
    }

    "not change context state if it is stopped" in {
      val (_, finalContext) = createAndUpdateThrough(Terminated, Running)
      finalContext.state mustEqual Terminated
    }
  }

  def createAndUpdateThrough(through: ContextState, to: ContextState, lastDetails: String = "") = {
    val initialContext = fixtures.contextEntity
    Await.result(db.run(contexts += initialContext), timeout.duration)

    updateContextState(initialContext.id, through, db)
    updateContextState(initialContext.id, to, db, lastDetails)

    val finalContext = Await.result(
      db.run(contexts.filter(_.id === initialContext.id).result),
      timeout.duration
    ).head

    (initialContext, finalContext)
  }
}
