package spark.job.rest.api

import org.apache.spark.sql.hive.HiveContext

trait SparkHiveJob extends SparkJobBase {
  type C = HiveContext
}
