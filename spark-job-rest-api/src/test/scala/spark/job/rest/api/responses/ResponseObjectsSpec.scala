package spark.job.rest.api.responses

import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{MustMatchers, WordSpec}
import spark.job.rest.api.entities.JobDetails

@RunWith(classOf[JUnitRunner])
class ResponseObjectsSpec extends WordSpec with MustMatchers {
  "Job" should {
    "convert from JobDetails without errors" in {
      Job.fromJobDetails(JobDetails("java.utils.UUID", ConfigFactory.empty()))
      Job.fromJobDetails(JobDetails("java.utils.UUID", ConfigFactory.empty(), startTime = Some(1)))
      Job.fromJobDetails(JobDetails("java.utils.UUID", ConfigFactory.empty(), startTime = Some(1), stopTime = Some(10)))
    }
  }
}
