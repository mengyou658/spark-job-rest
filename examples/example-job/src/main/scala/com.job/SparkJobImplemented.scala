package com.job

import com.typesafe.config.Config
import org.apache.spark.SparkContext
import spark.job.rest.api.{SparkJob, SparkJobInvalid, SparkJobValid, SparkJobValidation}

/**
 * Very basic Spark Job REST job example
 */
class SparkJobImplemented extends SparkJob
{
  override def runJob(sc: SparkContext, jobConfig: Config): Any = {

    val nr = jobConfig.getInt("input")

    val list = Range(0,nr)
    val rdd = sc.parallelize(list)
    rdd.count()

  }

  override def validate(sc: SparkContext, config: Config): SparkJobValidation = {
    if(config.hasPath("input")) SparkJobValid else SparkJobInvalid("The input parameter is missing.")
  }
}
