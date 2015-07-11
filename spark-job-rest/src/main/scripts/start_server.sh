#!/bin/bash
# Script to start the job server
# Extra arguments will be spark-submit options, for example
#  ./server_start.sh --jars cassandra-spark-connector.jar
set -e

get_abs_script_path() {
  pushd . >/dev/null
  cd $(dirname $0)
  appdir=$(pwd)
  popd  >/dev/null
}
get_abs_script_path

parentdir="$(dirname "$appdir")"

# From this variable depends whether server will be started in detached on in-process mode
SJR_RUN_DETACHED="${SJR_RUN_DETACHED-true}"

DRIVER_MEMORY=1g

GC_OPTS="-XX:+UseConcMarkSweepGC
         -verbose:gc -XX:+PrintGCTimeStamps -Xloggc:$appdir/gc.out
         -XX:MaxPermSize=512m
         -XX:+CMSClassUnloadingEnabled "

JAVA_OPTS="-Xmx1g -XX:MaxDirectMemorySize=512M
           -XX:+HeapDumpOnOutOfMemoryError -Djava.net.preferIPv4Stack=true
           -Dcom.sun.management.jmxremote.authenticate=false
           -Dcom.sun.management.jmxremote.ssl=false"

MAIN="spark.job.rest.server.Main"

conffile="$parentdir/resources/application.conf"

if [ ! -f "$conffile" ]; then
  echo "No configuration file $conffile found"
  exit 1
fi

if [ -f "$parentdir/resources/settings.sh" ]; then
  . $parentdir/resources/settings.sh
else
  echo "Missing $parentdir/resources/settings.sh, exiting"
  exit 1
fi

if [ -z "$SPARK_HOME" ]; then
  echo "Please set SPARK_HOME or put it in $parentdir/resources/settings.sh first"
  exit 1
fi

# Pull in other env vars in spark config, such as MESOS_NATIVE_LIBRARY
if [ -f $SPARK_CONF_HOME/spark-env.sh ]; then
    . $SPARK_CONF_HOME/spark-env.sh
else
    echo "Warning! '$SPARK_CONF_HOME/spark-env.sh' is not exist. Check SPARK_CONF_HOME or create the file."
fi

# Create directories if not exist
mkdir -p "${LOG_DIR}"
mkdir -p "${JAR_PATH}"
mkdir -p "${DATABASE_ROOT_DIR}"

LOG_FILE="spark-job-rest.log"
LOGGING_OPTS="-Dlog4j.configuration=log4j.properties
              -DLOG_DIR=${LOG_DIR}
              -DLOG_FILE=${LOG_FILE}"

# For Mesos
CONFIG_OVERRIDES=""
if [ -n "$SPARK_EXECUTOR_URI" ]; then
  CONFIG_OVERRIDES="-Dspark.executor.uri=$SPARK_EXECUTOR_URI "
fi
# For Mesos/Marathon, use the passed-in port
if [ "$PORT" != "" ]; then
  CONFIG_OVERRIDES+="-Dspark.jobserver.port=$PORT "
fi

# Need to explicitly include app dir in classpath so logging configs can be found
CLASSPATH="${parentdir}/${SJR_SERVER_JAR_NAME}:${appdir}/..:${appdir}/../resources"

# Log classpath
echo "CLASSPATH = ${CLASSPATH}" >> "${LOG_DIR}/${LOG_FILE}"

# The following should be exported in order to be accessible in Config substitutions
export SPARK_HOME
export APP_DIR
export JAR_PATH
export CONTEXTS_BASE_DIR
export DATABASE_ROOT_DIR
export SPARK_JOB_REST_HOME="${appdir}/.."
export SPARK_JOB_REST_CONTEXT_START_SCRIPT="${appdir}/../resources/context_start.sh"

function start_server() {
    # Start application using `spark-submit` which takes cake of computing classpaths
    "${SPARK_HOME}/bin/spark-submit" \
      --class $MAIN \
      --driver-memory $DRIVER_MEMORY \
      --conf "spark.executor.extraJavaOptions=${LOGGING_OPTS}" \
      --conf "spark.driver.extraClassPath=${CLASSPATH}" \
      --driver-java-options "${GC_OPTS} ${JAVA_OPTS} ${LOGGING_OPTS} ${CONFIG_OVERRIDES}" \
      $@ "${parentdir}/${SJR_SERVER_JAR_NAME}" \
      $conffile >> "${LOG_DIR}/${LOG_FILE}" 2>&1
}

if [ "${SJR_RUN_DETACHED}" = "true" ]; then
    start_server &
    echo $! > "${appdir}/server.pid"
    echo "Server started in detached mode. PID = `cat "${appdir}/server.pid"`"
elif [ "${SJR_RUN_DETACHED}" = "false" ]; then
    start_server
else
    echo "Wrong value for SJR_RUN_DETACHED = ${SJR_RUN_DETACHED}."
    exit -1
fi