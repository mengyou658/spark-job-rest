package spark.job.rest.context

import com.typesafe.config.Config
import org.apache.spark.SparkContext
import org.slf4j.LoggerFactory
import spark.job.rest.api.{ContextLike, SparkJob, SparkJobBase}
import spark.job.rest.utils.ContextUtils.configToSparkConf

/**
 * This is a default implementation for Spark Context factory.
 */
class SparkContextFactory extends JobContextFactory {
  type C = SparkContext with ContextLike
  private val log = LoggerFactory.getLogger(getClass)
  
  def makeContext(config: Config, contextName: String) = {
    val sparkConf = configToSparkConf(config, contextName)
    log.info(s"Creating Spark context $contextName with config:\n${sparkConf.toDebugString}.")
    new SparkContext(sparkConf) with ContextLike {
      val contextClass = classOf[SparkContext].getName
      def sparkContext: SparkContext = this.asInstanceOf[SparkContext]
      def isValidJob(job: SparkJobBase) = job.isInstanceOf[SparkJob]
    }
  }
}
