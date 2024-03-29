[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/Atigeo/spark-job-rest?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)

## Features:

**Supports multiple spark contexts created from the same server**

The main problem this project solves is the inability to run multiple Spark contexts from the same JVM. This is a bug in Spark core that was also present in Ooyala's Spark Job Server, from which this project is inspired. The project launches a new process for each Spark context/application, with its own driver memory setting and its own driver log. Each driver JVM is created with its own Spark UI port, sent back to the api caller. Inter-process communication is achieved with akka actors, and each process is shut down when a Spark context/application is deleted.

## Version compatibility

SJR Version   | Spark Version
------------- | -------------
0.3.0         |  1.1.0 
0.3.1         |  1.3.1 
0.3.3         |  1.4.0 

## Building Spark-Job-Rest (SJR)

The project is build with Maven3 and Java7.
```
make build
```
SJR can now be deployed from `spark-job-rest/spark-job-rest/target/scala-2.10/spark-job-rest.zip`

If running from IDE fails with:
```
Exception in thread "main" java.lang.NoClassDefFoundError: akka/actor/Props
```
This happens because the spark dependency has the provided scope. In order to run from IDE you can remove the provided scope (check `project/BuildSugar.scala`) for the spark dependency or you can add the spark assembly jar to the running classpath.

## Deploying Spark-Job-Rest

You can deploy Spark-Job-Rest locally to `deploy` directory inside the project by:
```sh
make deploy
```
Optionally you can specifying install directory in `$SJR_DEPLOY_PATH` environment variable:
```sh
SJR_DEPLOY_PATH=/opt/spark-job-rest make deploy
```

Before running JSR ensure that [working environment](#configure-spark-environment) is configured.

In order to have a proper installation you should set `$SPARK_HOME` to your Apache Spark distribution and `$SPARK_CONF_HOME` to directory which consists `spark-env.sh` (usually `$SPARK_HOME/conf` or `$SPARK_HOME/libexec/conf`).
You can do it in your bash profile (`~/.bash_profile` or `~/.bashrc`) by adding the following lines:
```sh
export SPARK_HOME=<Path to Apache Spark>
export SPARK_CONF_HOME=$SPARK_HOME/libexec/conf  # or $SPARK_HOME/conf depending on your distribution
```
After that either run in the new terminal session or source your bash profile.

### Deploying to remote host

You can deploy Spark-Job-REST to remote host via:
```sh
make remote-deploy
```

For remote deployment you should set following environment variables:
```sh
# Mandatory connection string
export SJR_DEPLOY_HOST=<user@hostname for remote machine>
# Optional parameters
export SJR_DEPLOY_KEY=<optional path to your SSH key>
export SJR_REMOTE_DEPLOY_PATH=<where you want to install Spark-Job-REST on remote host>
```
If `SJR_REMOTE_DEPLOY_PATH` is not set then `SJR_DEPLOY_PATH` will be used during remote deploy.

## Deploying from artifacts

You can deploy by putting `spark-job-rest-server.zip` (with optional `spark-job-rest-sql.zip`) and `deploy.sh` to one directory. Then perform:

```sh
sh deploy.sh
```

That will extract everything to `<files location/spark-job-rest>` and set proper file permissions.
 
## Starting Spark-Job-Rest

To start/stop SJR use
```sh
cd $SJR_DEPLOY_PATH
bin/start_server.sh
bin/stop_server.sh
```

or if it deployed to default destination just
```sh
make start
make stop
```

### Run in non-detached mode

By default server runs in detached mode. To turn it off simply set `SJR_RUN_DETACHED` to `false`.

## Configure Spark-job-rest

Spark-Job-REST default configuration is stored in `resources/application.conf` (here and after under `spark-job-rest/src/main/`).
To add or override settings create `resources/deploy.conf` (ignored by VCS).

### Spark context settings
Configure the default spark properties for context creation as they are normal Spark configuration options
```
spark.executor.memory=2g
spark.master="local"
spark.path="/Users/user/spark-1.1.0"
........
```
To set how much memory should be allocated for driver use `driver.xmxMemory` (default is `1g`).

### Application settings

Configure settings like web server port and akka system ports
```
spark.job.rest.appConf{
  web.services.port=8097
  spark.ui.first.port = 16000
  ........
}
```

### Configure folders & class paths

You may configure folders by setting environment variables and by creating and editing `resources/deploy-settings.sh` (under `spark-job-rest/src/main/`):

```sh
export SJR_LOG_DIR=<path to logs directory>
export SJR_JAR_PATH=<path to jar files storage>
export SJR_CONTEXTS_BASE_DIR=<path to the rood directory for contexts process directories>
export JSR_EXTRA_CLASSPATH=<additional classes required for your application to run>
export SJR_DATABASE_ROOT_DIR=<directory where database storage will be created>
```

### Java & GC options

You can extend or override Java and GC options in `resources/deploy-settings.sh`:

```sh
JAVA_OPTS="${JAVA_OPTS}
           ${YOUR_EXTRA_JAVA_OPTIONS}"
GC_OPTS="${GC_OPTS}
         ${YOUR_EXTRA_GC_OPTIONS}"           
```

## Custom contexts

Spark-Job-REST supports custom job context factories defined in `context.job-context-factory` property of config.
By default SJR uses `SparkContextFactory` which creates one Spark Context per JVM.

### SQL contexts

To run jobs with provided SQL contexts include `spark-job-rest-sql` in your project, set context factory to one of SQLContext factories provided by this library and inherit your job from `api.SparkSqlJob`.
Currently supported contexts:

1. `spark.job.rest.context.SparkSqlContextFactory` creates simple job SQLContext.
2. `spark.job.rest.HiveContextFactory` creates Hive SQL context.

## Configure Spark environment

In order to have a proper installation you should set `$SPARK_HOME` to your Apache Spark distribution and `$SPARK_CONF_HOME` to directory which consists `spark-env.sh` (usually `$SPARK_HOME/conf` or `$SPARK_HOME/libexec/conf`).
You can do it in your bash profile (`~/.bash_profile` or `~/.bashrc`) by adding the following lines:
```sh
export SPARK_HOME=<Path to Apache Spark>
export SPARK_CONF_HOME=$SPARK_HOME/libexec/conf  # or $SPARK_HOME/conf depending on your distribution
```
After that either run in the new terminal session or source your bash profile.

The SJR can be run from outside the Spark cluster, but you need to at least copy the deployment folder from one of the slaves or master nodes.

## Run Spark-job-rest

After editing all the configuration files SJR can be run by executing the script `start-server.sh`

The UI can be accessed at `<server address>:<appConf.web.services.port>`.

## API

**Contexts**

- POST /contexts/{contextName}  -  Create Context

 * Body:  Raw entity with key-value pairs. 
 * jars key is required and it should be in the form of a comma separated list of jar paths. These jars will be added at Spark context creation time to the class path of the newly created context's JVM process. There are 3 types of jar paths supported:
    * Absolute path on the server side : /home/ubuntu/example.jar
    * Name of the jar that was uploaded to the server : example.jar
    * Hdfs path : hdfs://devbox.local:8020/user/test/example.jar
  
  ``` 
 # Body example:
 jars="/home/ubuntu/example.jar,example.jar,hdfs://devbox.local:8020/user/test/example.jar”
 spark.executor.memory=2g
 driver.xmxMemory = 1g
  ```

- GET /contexts/{contextName}  -  returns Context JSON object | No such context.

- DELETE /contexts/{contextName}  -  Delete Context

**Jobs**

- POST /jobs?runningClass={runningClass}&context={contextName}  - Job Submission 

  * Body:  Raw entity with key-value pairs. Here you can set any configuration properties that will be passed to the config parameter of the validate and run methods of the provided jar (see the SparkJob definition below)

- GET /jobs/{jobId}?contextName={contextName} - Gets the result or state of a specific job

- GET /jobs - Gets the states/results of all jobs from all running contexts 

**Jars**

- POST /jars/{jarName}  - Upload jar
  * Body: Jar Bytes
  
- POST /jars  - Upload jar
  * Body: MultiPart Form

- GET /jars - Gets all the uploaded jars

- DELETE /jars/{jarName} - Delete jar

## HTTP Client

All the API methods can be called from Scala/Java with the help of an HTTP Client.

Maven Spark-Job-Rest-Client dependency:
```xml
<dependency>
    <groupId>com.xpatterns</groupId>
    <artifactId>spark-job-rest-client</artifactId>
    <version>0.3.3</version>
</dependency>
```

## Create Spark Job Project

Add maven Spark-Job-Rest-Api dependency:
```xml
<dependency>
    <groupId>com.xpatterns</groupId>
    <artifactId>spark-job-rest-api</artifactId>
    <version>0.3.3</version>
</dependency>
```

To create a job that can be submitted through the server, the class must implement the SparkJob trait.

```scala
import com.typesafe.config.Config
import org.apache.spark.SparkContext
import spark.job.rest.api.{SparkJobInvalid, SparkJobValid, SparkJobValidation, SparkJob}

class Example extends SparkJob {
    override def runJob(sc:SparkContext, jobConfig: Config): Any = { ... }
    override def validate(sc:SparkContext, config: Config): SparkJobValidation = { ... }
}
```

- runJob method contains the implementation of the Job. SparkContext and Config objects are provided through parameters.
- validate method allows for an initial validation. In order to run the job return SparkJobValid(), otherwise return SparkJobInvalid(message).

## Example

An example for this project can be found here: ```spark-job-rest/examples/example-job```. In order to package it, run 
```sh
mvn clean install
```

**Upload JAR**
```sh
# In the project root directory
curl --data-binary @spark-job-rest/examples/example-job/target/example-job.jar 'localhost:8097/jars/example-job.jar'

{
  "contextName": "test-context",
  "sparkUiPort": "16003"
}
```

**Create a context**
```sh
curl -X POST -d "jars=example-job.jar" 'localhost:8097/contexts/test-context'

{
  "contextName": "test-context",
  "sparkUiPort": "16003"
}
```

**Check if context exists**

```sh
curl 'localhost:8097/contexts/test-context'

{
  "contextName": "test-context",
  "sparkUiPort": "16003"
}
```

**Run job** - The example job creates an RDD from a Range(0,input) and applies count on it.

```sh
curl -X POST -d "input=10000" 'localhost:8097/jobs?runningClass=com.job.SparkJobImplemented&contextName=test-context'

{
  "jobId": "2bd438a2-ac1e-401a-b767-5fa044b2bd69",
  "contextName": "test-context",
  "status": "Running",
  "result": "",
  "startTime": 1430287260144
}
```

```2bd438a2-ac1e-401a-b767-5fa044b2bd69``` represents the jobId. This id can be used to query for the job status/results.

**Query for results**

```sh
curl 'localhost:8097/jobs/2bd438a2-ac1e-401a-b767-5fa044b2bd69?contextName=test-context'

{
  "jobId": "2bd438a2-ac1e-401a-b767-5fa044b2bd69",
  "contextName": "test-context",
  "status": "Finished",
  "result": "10000",
  "startTime": 1430287261108
}
```

**Delete context**

```sh
curl -X DELETE 'localhost:8097/contexts/test-context'

{
  "message": "Context deleted."
}
```

**HTTP Client Example**

```scala
object Example extends App {
  implicit val system = ActorSystem()
  val contextName = "testContext"

  try {
    val sjrc = new SparkJobRestClient("http://localhost:8097")

    val context = sjrc.createContext(contextName, Map("jars" -> "/Users/raduchilom/projects/spark-job-rest/examples/example-job/target/example-job.jar"))
    println(context)

    val job = sjrc.runJob("com.job.SparkJobImplemented", contextName, Map("input" -> "10"))
    println(job)

    var jobFinal = sjrc.getJob(job.jobId, job.contextName)
    while (jobFinal.status.equals(JobStates.RUNNING.toString())) {
      Thread.sleep(1000)
      jobFinal = sjrc.getJob(job.jobId, job.contextName)
    }
    println(jobFinal)

    sjrc.deleteContext(contextName)
  } catch {
    case e:Exception => {
      e.printStackTrace()
    }
  }

  system.shutdown()
}
```
Running this would produce the output:

```
Context(testContext,16002)
Job(ab63c19f-bbb4-461e-8c6f-f0a35f73a943,testContext,Running,,1430291077689)
Job(ab63c19f-bbb4-461e-8c6f-f0a35f73a943,testContext,Finished,10,1430291078694)
```


## UI

The UI was added in a compiled and minified state. For sources and changes please refer to [spark-job-rest-ui](https://github.com/marianbanita82/spark-job-rest-ui) project.
