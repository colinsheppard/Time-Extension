package org.nlogo.extensions.time.datatypes

import java.util.{ArrayList, Iterator, TreeSet}
import org.nlogo.agent.{Agent, AgentIterator, ArrayAgentSet, TickCounter, TreeAgentSet, World, AgentSet}
import org.nlogo.nvm.AnonymousCommand
import org.nlogo.api.{AnonymousProcedure, Argument, Context, ExtensionException, LogoException}
import org.nlogo.core.{AgentKindJ, ExtensionObject, LogoList}
import org.nlogo.nvm.ExtensionContext
import org.nlogo.extensions.time._
import scala.collection.JavaConverters._

class LogoSchedule extends ExtensionObject {
  var scheduleTree: TreeSet[LogoEvent] = new TreeSet[LogoEvent](LogoEventComparator)
  var tickCounter: TickCounter = null
  // The following three fields track an anchored schedule
  var timeAnchor: LogoTime = null
  var tickType: PeriodType = null
  var tickValue: java.lang.Double = null
  override def equals(obj: Any): Boolean = this == obj
  def isAnchored(): Boolean = timeAnchor != null

  def anchorSchedule(time: LogoTime,
                     tickValue: java.lang.Double, tickType: PeriodType): Unit =
    try {
      this.timeAnchor = new LogoTime(time)
      this.tickType = tickType
      this.tickValue = tickValue
    } catch {
      case e: ExtensionException => e.printStackTrace()

    }

  def timeToTick(time: LogoTime): java.lang.Double = {
    if (this.timeAnchor.dateType != time.dateType)
      throw new ExtensionException(
        "Cannot schedule event to occur at a LogoTime of type " +
          time.dateType.toString +
          " because the schedule is anchored to a LogoTime of type " +
          this.timeAnchor.dateType.toString +
          ".  Types must be consistent.")
    this.timeAnchor.getDifferenceBetween(this.tickType, time) /
      this.tickValue
  }

  def addEvent(args: Array[Argument],
               context: Context,
               addType: AddType): Unit = {
    var primName: String = null
    var eventTick: java.lang.Double = null
    addType match {
      case Default =>
        primName = "add"
        if (args.length < 3)
          throw new ExtensionException(
            "time:add must have 3 arguments: schedule agent task tick/time")
      case Shuffle =>
        primName = "add-shuffled"
        if (args.length < 3)
          throw new ExtensionException(
            "time:add-shuffled must have 3 arguments: schedule agent task tick/time")
      case Repeat =>
        primName = "repeat"
        if (args.length < 4)
          throw new ExtensionException(
            "time:repeat must have 4 or 5 arguments: schedule agent task tick/time number (period-type)")
      case RepeatShuffled =>
        primName = "repeat-shuffled"
        if (args.length < 4)
          throw new ExtensionException(
            "time:repeat-shuffled must have 4 or 5 arguments: schedule agent task tick/time number (period-type)")

    }
    if (!(args(0).get.isInstanceOf[Agent]) && !(args(0).get
          .isInstanceOf[AgentSet]) &&
        !((args(0).get.isInstanceOf[String]) && args(0).get.toString
          .toLowerCase()
          .==("observer")))
      throw new ExtensionException(
        "time:" + primName +
          " expecting an agent, agentset, or the string \"observer\" as the first argument")
    if (!(args(1).get.isInstanceOf[AnonymousCommand]))
      throw new ExtensionException(
        "time:" + primName + " expecting a command task as the second argument")
    if (args(1).get.asInstanceOf[AnonymousCommand].formals.length >
          0)
      throw new ExtensionException(
        "time:" + primName +
          " expecting as the second argument a command task that takes no arguments of its own, but found a task which expects its own arguments, this kind of task is unsupported by the time extension.")
    if (args(2).get.getClass == classOf[Double]) {
      eventTick = args(2).getDoubleValue
    } else if (args(2).get.getClass == classOf[LogoTime]) {
      if (!this.isAnchored)
        throw new ExtensionException(
          "A LogoEvent can only be scheduled to occur at a LogoTime if the discrete event schedule has been anchored to a LogoTime, see time:anchor-schedule")
      eventTick = this.timeToTick(TimeUtils.getTimeFromArgument(args, 2))
    } else {
      throw new ExtensionException(
        "time:" + primName +
          " expecting a number or logotime as the third argument")
    }
    if (eventTick <
          context.asInstanceOf[ExtensionContext].workspace.world.ticks)
      throw new ExtensionException(
        "Attempted to schedule an event for tick " + eventTick +
          " which is before the present 'moment' of " +
          context.asInstanceOf[ExtensionContext].workspace.world.ticks)
    var repeatIntervalPeriodType: PeriodType = null
    var repeatInterval: java.lang.Double = null
    if (addType == Repeat || addType == RepeatShuffled) {
      if (args(3).get.getClass != classOf[Double])
        throw new ExtensionException(
          "time:repeat expecting a number as the fourth argument")
      repeatInterval = args(3).getDoubleValue
      if (repeatInterval <= 0)
        throw new ExtensionException(
          "time:repeat the repeat interval must be a positive number")
      if (args.length == 5) {
        if (!this.isAnchored)
          throw new ExtensionException(
            "A LogoEvent can only be scheduled to repeat using a period type if the discrete event schedule has been anchored to a LogoTime, see time:anchor-schedule")
        repeatIntervalPeriodType = TimeUtils.stringToPeriodType(
          TimeUtils.getStringFromArgument(args, 4))
        if (repeatIntervalPeriodType != Month && repeatIntervalPeriodType != Year) {
          repeatInterval = this.timeAnchor.getDifferenceBetween(
              this.tickType,
              this.timeAnchor.plus(repeatIntervalPeriodType, repeatInterval)) /
              this.tickValue
          if (TimeExtension.debug)
            TimeUtils.printToConsole(
              context,
              "from:" + repeatIntervalPeriodType + " to:" + this.tickType +
                " interval:" +
                repeatInterval)
          repeatIntervalPeriodType = null
        } else {
          if (TimeExtension.debug)
            TimeUtils.printToConsole(
              context,
              "repeat every: " + repeatInterval + " " + repeatIntervalPeriodType)
        }
      }
    }
    val shuffleAgentSet: Boolean = (addType == Shuffle || addType == RepeatShuffled)
    var agentSet: AgentSet = null
    args(0).get match {
      case agent: Agent =>
        val theAgent: Agent = args(0).getAgent.asInstanceOf[org.nlogo.agent.Agent]
        agentSet = new ArrayAgentSet(AgentKindJ.Turtle, theAgent.toString, Array(theAgent))
      case agentset: AgentSet =>
        agentSet = args(0).getAgentSet.asInstanceOf[AgentSet]
      case _ =>
    }
    val event: LogoEvent =
      new LogoEvent(
        agentSet,
        args(1).getCommand.asInstanceOf[AnonymousCommand],
        eventTick,
        repeatInterval,
        repeatIntervalPeriodType,
        shuffleAgentSet)
    if (TimeExtension.debug)
      TimeUtils
        .printToConsole(context,"scheduling event: " + event.dump(false, false, false))
    scheduleTree.add(event)
  }

  def getTickCounter(): TickCounter =
    tickCounter match {
      case tc if tc == null =>
        throw new ExtensionException(
          "Tick counter has not been initialized in time extension.")
      case tc => tc
    }

  def getTickCounter(context: ExtensionContext): TickCounter =
    tickCounter match {
      case tc if tc == null =>
        throw new ExtensionException(
          "Tick counter has not been initialized in time extension.")
      case tc => context.workspace.world.tickCounter
    }

  def performScheduledTasks(args: Array[Argument], context: Context): Unit =
    performScheduledTasks(args, context, java.lang.Double.MAX_VALUE)

  def performScheduledTasks(args: Array[Argument],
                            context: Context,
                            untilTime: LogoTime): Unit = {
    if (!this.isAnchored)
      throw new ExtensionException("time:go-until can only accept a LogoTime as a stopping time if the schedule is anchored using time:anchore-schedule")
    if (TimeExtension.debug)
      TimeUtils.printToConsole(
        context,
        "timeAnchor: " +  this.timeAnchor +
          " tickType: " + this.tickType   +
          " tickValue:" + this.tickValue  +
          " untilTime:" +      untilTime)
    val untilTick: java.lang.Double =
      this.timeAnchor.getDifferenceBetween(this.tickType, untilTime) /
        this.tickValue
    performScheduledTasks(args, context, untilTick)
  }

  def performScheduledTasks(args: Array[Argument],
                            context: Context,
                            untilTick: java.lang.Double): Unit = {
    val extcontext: ExtensionContext = context.asInstanceOf[ExtensionContext]
    // This extension is only for CommandTasks, so we know there aren't any args to pass in
    val emptyArgs: Array[Any] = Array.ofDim[Any](1)
    var event: LogoEvent =
      if (scheduleTree.isEmpty) null else scheduleTree.first()
    val theAgents: ArrayList[org.nlogo.agent.Agent] =
      new ArrayList[org.nlogo.agent.Agent]()
    while (event != null && event.tick <= untilTick) {
      if (TimeExtension.debug)
        TimeUtils.printToConsole(
          context,
          "performing event-id: " + event.id + " for agent: " +
            event.agents +
            " at tick:" +
            event.tick +
            " ")
      if (TimeExtension.debug)
        TimeUtils.printToConsole(
          context,
          "tick counter before: " + getTickCounter(extcontext) +
            ", " +
            getTickCounter(extcontext).ticks)
      getTickCounter(extcontext).tick(
        event.tick - getTickCounter(extcontext).ticks)
      if (TimeExtension.debug)
        TimeUtils.printToConsole(
          context,
          "tick counter after: " + getTickCounter(extcontext) +
            ", " +
            getTickCounter(extcontext).ticks)
      if (event.agents == null) {
        if (TimeExtension.debug)
          TimeUtils.printToConsole(context, "single agent")
        val nvmContext: org.nlogo.nvm.Context = new org.nlogo.nvm.Context(
          extcontext.nvmContext.job,
          extcontext.getAgent
            .world
            .observer
            .asInstanceOf[org.nlogo.agent.Agent],
          extcontext.nvmContext.ip,
          extcontext.nvmContext.activation,
          extcontext.workspace
        )
        event.task.perform(nvmContext, emptyArgs.asInstanceOf[Array[AnyRef]])
      } else {
        var iter: AgentIterator = null
        iter =
          if (event.shuffleAgentSet)
            event.agents.shufflerator(extcontext.nvmContext.job.random)
          else event.agents.iterator
        val copy: ArrayList[Agent] = new ArrayList[Agent]()
        while (iter.hasNext) copy.add(iter.next())
        for (theAgent <- copy.asScala) {
          if (theAgent == null || theAgent.id == -1) {
          //continue
          }
          val nvmContext: org.nlogo.nvm.Context = new org.nlogo.nvm.Context(
            extcontext.nvmContext.job,
            theAgent,
            extcontext.nvmContext.ip,
            extcontext.nvmContext.activation,
            extcontext.workspace)
          if (extcontext.nvmContext.stopping){
            return
          }
          event.task.perform(nvmContext, emptyArgs.asInstanceOf[Array[AnyRef]])
          if (nvmContext.stopping){
            return
          }
        }
      }
      // Remove the current event as is from the schedule
      scheduleTree.remove(event)
      // Reschedule the event if necessary
      event.reschedule(this)
      // Grab the next event from the schedule
      event = if (scheduleTree.isEmpty) null else scheduleTree.first()
    }
    if (untilTick != null && untilTick < java.lang.Double.MAX_VALUE &&
        untilTick > getTickCounter(extcontext).ticks)
      getTickCounter(extcontext).tick(
        untilTick - getTickCounter(extcontext).ticks)
  }

  def getCurrentTime(): LogoTime = {
    if (!this.isAnchored) null
    if (TimeExtension.debug){
      TimeUtils.printToConsole(
        TimeExtension.context ,
        "current time is: " +
          this.timeAnchor.plus(this.tickType,
            getTickCounter.ticks / this.tickValue))
    }
    this.timeAnchor
      .plus(this.tickType, getTickCounter.ticks / this.tickValue)
  }

  def dump(readable: Boolean, exporting: Boolean, reference: Boolean): String = {
    val buf: StringBuilder = new StringBuilder()
    if (exporting) {
      buf.append("LogoSchedule")
      if (!reference) {
        buf.append(":")
      }
    }
    if (!(reference && exporting)) {
      buf.append(" [ ")
      val iter: java.util.Iterator[LogoEvent] = scheduleTree.iterator()
      while (iter.hasNext) {
        buf.append(iter.next().asInstanceOf[LogoEvent].dump(true, true, true))
        buf.append("\n")
      }
      buf.append("]")
    }
    buf.toString
  }

  def getExtensionName(): String = "time"
  def getNLTypeName(): String = "schedule"
  def recursivelyEqual(arg0: AnyRef): Boolean = equals(arg0)
  def clear(): Unit = {
    scheduleTree.clear()
  }
}
