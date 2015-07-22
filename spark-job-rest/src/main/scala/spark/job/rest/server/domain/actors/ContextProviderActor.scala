package spark.job.rest.server.domain.actors

import akka.actor._
import com.typesafe.config.Config
import spark.job.rest.api.entities.{ContextDetails, ContextState, Jars}
import spark.job.rest.api.responses.Context
import spark.job.rest.api.types._
import spark.job.rest.config.durations.Durations
import spark.job.rest.context._
import spark.job.rest.persistence.services.ContextPersistenceService
import spark.job.rest.persistence.slickWrapper.Driver.api._
import spark.job.rest.server.domain.actors.ContextApplicationActor.StartContext
import spark.job.rest.utils.JarUtils

/**
 * Base data trait for context provider.
 * In each state context provider has a process actor.
 */
sealed trait ContextProviderData

/**
 * Initial dummy state.
 */
case object Empty extends ContextProviderData

/**
 * Trait for data for states after successful start
 */
sealed trait SuccessfulStart extends ContextProviderData {
  val processActor: ActorRef
  val contextDetails: ContextDetails
}

/**
 * Trait for data which contains context application referenceю
 */
sealed trait ApplicationStarted extends ContextProviderData {
  val contextApp: ActorRef
}

/**
 * Data corresponded to initial [[StartingProcess]] state when process is just started.
 * @param contextDetails context details
 * @param processActor actor responsible for the process
 */
case class StartedProcess(contextDetails: ContextDetails, processActor: ActorRef) extends ContextProviderData with SuccessfulStart

/**
 * Data that corresponds to [[Initialising]] and [[StartingContext]] states when remote application
 * negotiates with master for configuration.
 * @param contextDetails context details
 * @param processActor actor responsible for the process
 * @param contextApp remote context application actor 
 */
case class RegisteredContextApplication(contextDetails: ContextDetails, processActor: ActorRef, contextApp: ActorRef) 
  extends ContextProviderData with SuccessfulStart with ApplicationStarted

/**
 * Data which correspond to running remote context in [[Running]].
 * @param contextDetails context details
 * @param processActor actor responsible for the process
 * @param contextApp remote context application actor 
 * @param jobs set of currently running jobs
 */
case class RunningContext(contextDetails: ContextDetails, processActor: ActorRef, contextApp: ActorRef, jobs: Map[ID, ActorRef])
  extends ContextProviderData with SuccessfulStart with ApplicationStarted

sealed trait ContextProviderState
case object Idle extends ContextProviderState
case object StartingProcess extends ContextProviderState
case object Initialising extends ContextProviderState
case object StartingContext extends ContextProviderState
case object Running extends ContextProviderState

object ContextProviderActor {
  // Initiates context creation
  case object Go
  // Registering application to master
  case class RegisterContextApplication(contextApp: ActorRef)
  // Initialising application
  case class ContextApplicationInitialized(sparkUiPor: Int)
  case class ContextApplicationInitFailed(message: String)
  // Starting context
  case class JobContextStarted(finalConfig: Config)
  case class JobContextStartFailed(message: String)
  // Process termination
  sealed trait ProcessStoppedOrFailed {
    val statusCode: Int
  }
  case class ProcessStopped(statusCode: Int) extends ProcessStoppedOrFailed
  case class ProcessFailed(statusCode: Int) extends ProcessStoppedOrFailed
  // Context info
  case object GetContextInfo
  // Context termination
  case object StopContext
}

class UnsupportedTransitionException(contextName: String, from: ContextProviderState, to: ContextProviderState)
  extends ContextException(contextName, s"Unsupported transition from $from to $to in $contextName context.")

class ContextProviderRestartIsNotAllowedException(contextName: String, cause: Throwable)
  extends ContextException(contextName, s"Restart of context provider is not alowed.")

/**
 * Context contextApp responsible for single context creation.
 * It starts context processActor via [[ContextProcessActor]] and initializes remote [[ContextApplicationActor]].
 * @param contextName context name
 * @param jars jars for context
 * @param submittedConfig config submitted by client
 * @param configDefaults configuration defaults obtained from application config
 * @param db database connection
 * @param connectionProvider connection provider
 */
class ContextProviderActor(contextName: String,
                           jars: Jars,
                           submittedConfig: Config,
                           configDefaults: Config,
                           db: Database,
                           connectionProvider: ActorRef)
  extends FSM[ContextProviderState, ContextProviderData] with JarUtils with ContextPersistenceService with Durations {
  // Internal imports
  import ContextApplicationActor.Initialize
  import ContextCreationSupervisor._
  import ContextProviderActor._

  // Assert that context provider has right path ending.
  assert(
    self.path.toStringWithoutAddress.contains(contextName),
    s"Address for context provider should contain context name but '${self.path.toStringWithoutAddress}' is given."
  )

  /**
   * Path to remote actor gateway for initialisation. Currently this actor.
   */
  val gatewayPath = self.path.toStringWithoutAddress

  /**
   * Config for config-dependent traits
   */
  val config = submittedConfig.withFallback(configDefaults)

  /**
   * Context provider starts from [[Idle]] state withing [[Empty]] data.
   */
  startWith(Idle, Empty)

  /**
   * We do not support restart of the context actor.
   * @param reason restart reason
   */
  override def postRestart(reason: Throwable): Unit = {
    log.error("Context provider restart is not supported", reason)
    val error = new ContextProviderRestartIsNotAllowedException(contextName, reason)
    stop(FSM.Failure(error)) replying error
  }

  /**
   * The [[Idle]] initial state responds only to a [[Go]] message, creates [[ContextProcessActor]] and moves to
   * [[StartingProcess]] state.
   */
  when(Idle) {
    case Event(Go, Empty) =>
      log.info(s"Context provider $self requested to create context $contextName.")
      try {
        // Persist initial context state
        val contextDetails = insertContext(ContextDetails(contextName, submittedConfig, None, jars), db)

        // Create process actor which asynchronously and starts the process
        val processActor = context.actorOf(Props(new ContextProcessActor(
          contextName,
          contextDetails.id,
          gatewayPath,
          getJarsPathForClasspath(jars, contextName),
          config)
        ), name = "ContextProcess")

        // Watch process actor termination
        context.watch(processActor)

        // Configure data for next FSM state and switch
        goto(StartingProcess) using StartedProcess(contextDetails, processActor) forMax durations.context.wakeupTimeout
      } catch {
        case e: Throwable =>
          stop(FSM.Failure(new ContextProcessStartException(contextName, e)))
      }
  }

  /**
   * In [[StartingProcess]] state [[ContextProviderActor]] wait while for remote processActor will start and registers back it's
   * [[ContextApplicationActor]]. After that [[ContextProviderActor]] sends initialisation message and transits to
   * [[Initialising]] state saving reference to context application actor in [[RegisteredContextApplication]].
   * All cases of processActor termination are considered as failures.
   */
  when(StartingProcess) {
    case Event(RegisterContextApplication(contextApp), StartedProcess(contextDetails, processActor)) =>
      val contextId = contextDetails.id
      log.info(s"Context application for $contextName:$contextId is registered from $contextApp.")

      // Persist context start and create data for the next state
      val newContextDetails = updateContextState(contextId, ContextState.Started, db, "Remote context application started.")
      val newData = RegisteredContextApplication(newContextDetails, processActor, contextApp)

      // Send init info to context application
      contextApp ! Initialize(contextName, contextId, connectionProvider, config, getJarsPathForSpark(contextDetails.jars))

      // Watch context application actor termination
      context.watch(contextApp)

      goto(Initialising) using newData forMax durations.context.initialisationTimeout

    case Event(failure: ProcessStoppedOrFailed, _) =>
      stop(FSM.Failure(new UnexpectedContextProcessStopException(contextName, failure.statusCode)))
  }

  /**
   * At [[Initialising]] state [[ContextProviderActor]] waits until remote context application actor finishes it's
   * initialisation and will be about to start context. Once [[ContextApplicationInitialized]] received we
   * switching to [[StartingContext]] state with updated context details in [[RegisteredContextApplication]].
   * All cases of processActor termination are considered as failures.
   */
  when(Initialising) {
    case Event(ContextApplicationInitialized(sparkUiPort), data: RegisteredContextApplication) =>
      val contextId = data.contextDetails.id
      log.info(s"Context application $contextName:$contextId is initialized and is about to start the context.")

      // Persist context initialisation and obtain data for new state
      val newData = data.copy(contextDetails = persistContextInitialisation(contextId, sparkUiPort, db))

      // Send start command
      data.contextApp ! StartContext

      // Switch to starting state
      goto(StartingContext) using newData forMax durations.context.startTimeout

    case Event(failure: ProcessStoppedOrFailed, _) =>
      stop(FSM.Failure(new UnexpectedContextProcessStopException(contextName, failure.statusCode)))
  }

  /**
   * At [[StartingContext]] state we just waiting for context to start and switching to [[Running]] after that.
   * All cases of processActor termination are considered as failures.
   */
  when(StartingContext) {
    case Event(JobContextStarted(finalConfig), RegisteredContextApplication(contextDetails, processActor, contextApp)) =>
      val contextId = contextDetails.id
      log.info(s"Context $contextName:$contextId successfully started.")
      goto(Running) using RunningContext(persistContextCreation(contextId, finalConfig, db), processActor, contextApp, Map.empty)
    
    case Event(error: JobContextStartException, RegisteredContextApplication(contextDetails, processActor, contextApp)) =>
      stop(FSM.Failure(error))

    case Event(failure: ProcessStoppedOrFailed, _) =>
      stop(FSM.Failure(new UnexpectedContextProcessStopException(contextName, failure.statusCode)))
  }

  /**
   * During [[Running]] state we accepting job requests to the context storing running job in a [[RunningContext]].
   */
  when(Running) {
    case Event(runJob: JobActor.RunJob, data: RunningContext) =>
      log.info(s"Received job request for job ${runJob.id} at $contextName.")
      data.contextApp ! runJob
      stay() using data.copy(jobs = data.jobs + (runJob.id -> sender()))

    /**
     * When job started we just forward [[JobActor.JobStarted]] message to job actor.
     */
    case Event(jobStarted: JobActor.JobStarted, data: RunningContext) =>
      data.jobs(jobStarted.jobId) ! jobStarted
      stay()

    /**
     * When job is finished we forward [[JobActor.JobResult]] to job actor and remove job from job list.
     */
    case Event(jobResult: JobActor.JobResult,  data: RunningContext) =>
      data.jobs(jobResult.jobId) ! jobResult
      stay() using data.copy(jobs = data.jobs - jobResult.jobId)

    /**
     * When job failed we forward [[JobActor.JobFailure]] to job actor and remove job from job list.
     */
    case Event(jobFailure: JobActor.JobFailure,  data: RunningContext) =>
      data.jobs(jobFailure.jobId) ! jobFailure
      stay() using data.copy(jobs = data.jobs - jobFailure.jobId)

    /**
     * When [[GetContextInfo]] received we construct [[Context]] response from corresponding [[ContextDetails]] in
     * our state data.
     */
    case Event(GetContextInfo, data: RunningContext) =>
      log.info(s"Received context info request for $contextName.")
      sender() ! Context(contextName, data.contextDetails.id, data.contextDetails.state, data.contextDetails.sparkUiPort)
      stay()

    /**
     * Process unexpected context process stop.
     */
    case Event(failure: ProcessStoppedOrFailed, _) =>
      stop(FSM.Failure(new UnexpectedContextProcessStopException(contextName, failure.statusCode)))
  }

  /**
   * Catch termination of child actors
   */
  whenUnhandled {
    /**
     * Watch unexpected process stop
     */
    case Event(reason @ Terminated(actor), data: SuccessfulStart) if actor.equals(data.processActor) =>
      val error = ContextCreationError(new RuntimeException(s"Unexpected termination of context process actor."))
      context.parent ! error
      stop(FSM.Failure(reason)) replying error

    /**
     * Watch unexpected context application stop
     */
    case Event(reason @ Terminated(actor), data: ApplicationStarted) if actor.equals(data.contextApp) =>
      val error = ContextCreationError(new RuntimeException(s"Unexpected termination of remote context application actor."))
      context.parent ! error
      stop(FSM.Failure(reason)) replying error
  }

  /**
   * Handling all termination cases.
   */
  onTermination {
    // Handling actor failure
    case StopEvent(FSM.Failure(reason: ContextException), state, data: SuccessfulStart) =>
      val contextId = data.contextDetails.id
      updateContextState(contextId, ContextState.Failed, db, s"Context failed at $state due to unclassified error: ${reason.getMessage}")
      context.parent ! reason

    // Shutdown cleanup
    case StopEvent(FSM.Shutdown, Running, data: SuccessfulStart) =>
      val contextId = data.contextDetails.id
      updateContextState(contextId, ContextState.Terminated, db, s"Context terminated by request.")

    // Guardian for unsupported suicide at any state
    case StopEvent(reason @ FSM.Normal, state, data: SuccessfulStart) =>
      val contextId = data.contextDetails.id
      updateContextState(contextId, ContextState.Failed, db, s"Context committed suicide at $state state.")
      context.parent ! new UnexpectedContextProviderStop(contextName, reason)

    // Guardian for everything else
    case StopEvent(reason, state, _) =>
      context.parent ! new UnexpectedContextProviderStop(contextName, reason)
  }

  /**
   * Here we list all allowed transitions and catch unsupported transitions.
   */
  onTransition {
    case (Idle, StartingProcess) =>
      log.info(s"Starting process for $contextName.")
    case (StartingProcess, Initialising) =>
      log.info(s"Initializing context application for $contextName.")
    case (Initialising, StartingContext) =>
      log.info(s"Starting job context for $contextName.")
    case (StartingContext, Running) =>
      val data = nextStateData.asInstanceOf[RunningContext]
      context.parent ! ContextStarted(Context.fromContextDetails(data.contextDetails))
    case (from, to) =>
      stop(FSM.Failure(new UnsupportedTransitionException(contextName, from, to)))
  }

  initialize()
}