package spark.job.rest.api

import org.apache.spark.sql.SQLContext

trait SparkSqlJob extends SparkJobBase {
  type C = SQLContext
}
