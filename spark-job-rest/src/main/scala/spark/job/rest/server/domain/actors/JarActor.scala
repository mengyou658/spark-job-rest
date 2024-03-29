package spark.job.rest.server.domain.actors

import java.io.File

import akka.actor.Actor
import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import spark.job.rest.api.responses.{JarInfo, JarsInfo}
import spark.job.rest.server.domain.actors.JarActor._
import spark.job.rest.utils.{FileUtils, JarUtils}

import scala.util.{Failure, Success, Try}

/**
 * Jar actor responsible for JARs manipulation.
 */
object JarActor {
  case class AddJar(jarName: String, bytes: Array[Byte])
  case class NoSuchJar()
  case class DeleteJar(jarName: String)
  case class GetAllJars()
  case class GetAllJarsNames()
  case class GetJarsPathForClasspath(paths: String, contextName: String)
  case class GetJarsPathForSpark(paths: String)
  case class GetJarsPathForAll(paths: String, contextName: String)
  case class DeleteJarFolder()
  case class CreateJarFolder(overwrite: Boolean)
  case class JarFolderExists()
  case class ResultJarsPathForAll(pathForClasspath: String, pathForSpark: List[String])

  val CLASSPATH_JAR_SEPARATOR = ":"
  val JAR_FOLDER_PROPERTY_PATH = "spark.job.rest.appConf.jars.path"
}

class JarActor(config: Config) extends Actor {

  val log = LoggerFactory.getLogger(getClass)

  val jarFolder = getValueFromConfig(config, JAR_FOLDER_PROPERTY_PATH, "")
  FileUtils.createFolder(jarFolder, overwrite = false)

  override def receive: Receive = {
    case AddJar(jarName, bytes) =>
      log.info(s"Received AddJar request for jar $jarName")
      Try {
        if(!JarUtils.validateJar(bytes)){
          log.error("Jar " + jarName + " is not valid!")
          throw new Exception("Jar " + jarName + " is not valid!")
        }
        FileUtils.writeToFile(jarName, jarFolder, bytes)
      } match {
        case Success(v) =>
          val fileJar = new File(jarFolder + File.separator + jarName)
          if(fileJar.exists()) {
            sender ! Success(JarInfo(jarName, fileJar.length(), fileJar.lastModified()))
          } else {
            sender ! Failure(new Exception("Jar was wrote to disk."))
          }
        case Failure(e) => sender ! Failure(e)
      }
    case DeleteJar(jarName) =>
      val file = new File(jarFolder + File.separator + jarName)
      if(file.exists()){
        file.delete()
        sender ! Success("Jar deleted.")
      } else {
        sender ! NoSuchJar()
      }
    case GetAllJars() =>
      val folderJar = new File(jarFolder)
      val files = folderJar.listFiles()
      if(files != null){
        val jarInfos = JarsInfo(files.map(jarFile => JarInfo(jarFile.getName, jarFile.length, jarFile.lastModified)).filter(_.name.endsWith(".jar")))
        sender ! jarInfos
      } else {
        sender ! List()
      }
    case GetAllJarsNames() =>
      val folderJar = new File(jarFolder)
      val files = folderJar.listFiles()
      if(files != null){
        val jarNames = files.map(_.getName).filter(_.endsWith(".jar")).toList
        sender ! jarNames
      } else {
        sender ! List()
      }
    case GetJarsPathForClasspath(path, contextName) =>
      Try {
        getJarsPathForClasspath(path, contextName)
      } match {
        case Success(classPath) => sender ! classPath
        case Failure(e) => sender ! e
      }
    case GetJarsPathForSpark(path) =>
      Try {
        sender ! getJarsPathForSpark(path)
      } match {
        case Success(classPath) => sender ! classPath
        case Failure(e) => sender ! e
      }

    case GetJarsPathForAll(paths: String, contextName: String) =>
      Try {
        ResultJarsPathForAll(getJarsPathForClasspath(paths, contextName), getJarsPathForSpark(paths))
      } match {
        case Success(result) => sender ! result
        case Failure(e) => sender ! e
      }

    case DeleteJarFolder() =>
      FileUtils.deleteFolder(jarFolder)

    case CreateJarFolder(overwrite: Boolean) =>
      FileUtils.createFolder(jarFolder, overwrite)

    case JarFolderExists() =>
      val file = new File(jarFolder)
      sender ! file.exists()
  }

  def getJarsPathForSpark(path: String): List[String] = {
    path.split(",") map { x: String =>
      JarUtils.getJarPathForSpark(x, jarFolder)
    } toList
  }

  def getJarsPathForClasspath(path: String, contextName: String) = {
    path.split(",") map { x =>
      JarUtils.getPathForClasspath(x, jarFolder, contextName)
    } mkString CLASSPATH_JAR_SEPARATOR
  }
}


