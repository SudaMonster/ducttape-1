package ducttape

import ducttape.syntax.{AbstractSyntaxTree => ast}
import ducttape.syntax.BashCode
import ducttape.workflow.Branch
import ducttape.workflow.BranchFactory
import ducttape.workflow.BranchPoint
import ducttape.workflow.Realization
import scala.collection.Map

class PackedGraph(val workflow:ast.WorkflowDefinition, val confSpecs: Seq[ast.ConfigAssignment]) {
  
  val branchFactory:BranchFactory = ducttape.cli.ErrorUtils.ex2err(ducttape.workflow.builder.WorkflowBuilder.findBranchPoints(confSpecs ++ Seq(workflow)))
  
  private val taskMap:Map[String, PackedGraph.Task] = PackedGraph.build(workflow, branchFactory)
  private val globalMap:Map[String, PackedGraph.Global] = PackedGraph.build(confSpecs, branchFactory) 
  
  lazy val numTasks:Int = taskMap.values.size
  
  /** 
   * Gets the [[ducttape.PackedGraph.Task]] associated with the provided task name. 
   * 
   * @param name Name of the task
   * @return the task 
   */
  def task(name:String): Option[PackedGraph.Task] = taskMap.get(name)

  
  /** 
   * Gets the [[ducttape.PackedGraph.Global]] associated with the provided global variable name. 
   * 
   * @param name Name of the task
   * @return the task 
   */
  def global(name:String): Option[PackedGraph.Global] = globalMap.get(name)
  
  
  
  override def toString(): String = PackedGraph.toGraphviz(taskMap, globalMap)
  
  lazy val goals = Goals.fromPlans(this)
  
  def plans = workflow.plans
  
}

object PackedGraph {
  
  sealed trait Node
  final case class Spec(name:String, value:ValueBearingNode)
  final case class CrossProduct(value:Set[Branch]) extends Node
  final case class Via(reach:Task, via:CrossProduct) extends Node
  final case class Plan(goals:Seq[Via]) extends Node
  final case class ShellScript(code: String, vars: Set[String]) extends Node
  final case class Packages(value:Seq[Package]) extends Node
  final case class Global(value:Spec) extends Node
  final case class Task(name:String, code:BashCode, inputs:Seq[Spec], params:Seq[Spec], outputs:Seq[Spec], packages:Seq[Spec]) extends Node {
    
    def containsVariable(variableName:String) : Boolean = {
      
      for (input  <- inputs)  { if (input.name  == variableName) { return true } }
      for (output <- outputs) { if (output.name == variableName) { return true } }
      for (param  <- params)  { if (param.name  == variableName) { return true } }
      
      return false
    }
    
  }

  sealed trait ValueBearingNode extends Node
  final case class Literal(value:String) extends ValueBearingNode
  final case class BranchNode(branch:Branch, value:ValueBearingNode) extends ValueBearingNode
  final case class BranchPointNode(branchPoint:BranchPoint, branches:Seq[BranchNode]) extends ValueBearingNode
  final case class Reference(variableName:String, taskName:Option[String], grafts:Seq[Grafts]) extends ValueBearingNode

  final case class Grafts(value:Seq[Branch]) {
    def size() = value.size
  }
  
  def toGraphvizID(taskName:String) : String = {
    return s"""task ${taskName}"""
  }
  
  def toGraphvizID(task:Task) : String = toGraphvizID(task.name)
  
  def toGraphviz(tasks:Map[String,Task], globals:Map[String, PackedGraph.Global]) : String = {
    val s = new StringBuilder(capacity=1000)
    s ++= "digraph G {\n\n"
    
    def recursivelyBuildTask(targetID:String, rhs:ValueBearingNode, s:StringBuilder) : Unit = {
      rhs match {
        case Literal(value) => {
          val id = targetID + " value " + value
          s.append("\t\t\"").append(id).append("""" [margin="0.01,0.01", height=0.0, width=0.0, fontcolor=black, fillcolor=darkseagreen2, color=black, shape=box, style="filled", label="""").append(value).append("\"]\n")
          s.append("\t\t\"").append(id).append("\" -> \"").append(targetID).append("\"\n")
        }
        
        case BranchNode(branch, value) => {
          val id = targetID + " branch " + branch.name
          s.append("\t\t\"").append(id).append("""" [margin="0.01,0.01", height=0.0, width=0.0, fontcolor=white, fillcolor=blue, color=blue, style="filled", label="""").append(branch.name).append("\"]\n")
          recursivelyBuildTask(id, value, s)
          s.append("\t\t\"").append(id).append("\" -> \"").append(targetID).append("\"\n")
        }
        
        case BranchPointNode(branchPoint, branches) => {
          val id = targetID + " branchPoint " + branchPoint.name
          s.append("\t\t\"").append(id).append("""" [margin="0.01,0.01", height=0.0, width=0.0, fontcolor=white, fillcolor=red, color=red, shape=box, style="filled", label="""").append(branchPoint.name).append("\"]\n")
          for (branch <- branches) {
            recursivelyBuildTask(id, branch, s)
          }
          s.append("\t\t\"").append(id).append("\" -> \"").append(targetID).append("\"\n")
        }
        
        case _ => {}
      }
      
      
    }  

    
    def recursivelyConnect(targetID:String, rhs:ValueBearingNode, s:StringBuilder) : Unit = {
      rhs match {
        
        case BranchNode(branch, value) => {
          val id = targetID + " branch " + branch.name
          recursivelyConnect(id, value, s)
        }
        
        case BranchPointNode(branchPoint, branches) => {
          val id = targetID + " branchPoint " + branchPoint.name
          for (branch <- branches) {
            recursivelyConnect(id, branch, s)
          }
        }
        
        case Reference(variable, taskName, graftsList) => {
          val id = taskName match {
            case Some(task) => variable + "@" + toGraphvizID(task)
            case None       => "global " + variable
          }
          if (graftsList.isEmpty) {
            s.append("\t\"").append(id).append("\" -> \"").append(targetID).append("\"").append("\n")
          } else {
            for ((grafts,index) <- graftsList.zipWithIndex) {
              if (grafts.value.size > 0) {
                val label = grafts.value.map{ graft => graft.toString}.mkString(",")
                val graftID = "from " + id + " graft " + index + " to " + targetID
                s.append("\t\"").append(graftID).append("""" [margin="0.0,0.0", height=0.0, width=0.0, fontcolor=black, fillcolor=azure, color=black, style="filled", shape=box, label="""").append(label).append("\"]\n")
                s.append("\t\"").append(id).append("\" -> \"").append(graftID).append("\"").append("\n")
                s.append("\t\"").append(graftID).append("\" -> \"").append(targetID).append("\"").append("\n")
              } else {
                s.append("\t\"").append(id).append("\" -> \"").append(targetID).append("\"").append("\n")
              }
              
            }
          }
        }
       
        case _ => {}
      }
      
      
    }      
    
    
    
    for (global <- globals.values) {
      val spec = global.value
      if (! spec.name.startsWith("ducttape_")) {
        val id = "global " + spec.name
        s.append("\tsubgraph \"cluster ").append(id).append("\" {\n")
        s.append("\t\t\"").append(id).append("""" [margin="0.01,0.01", height=0.0, width=0.0, fontcolor=black, fillcolor=white, color=black, style=filled, shape="box", label="""").append(spec.name).append("\"]\n")
        s.append("\t\tcolor=black\n\t\tfillcolor=beige\n\t\tstyle=filled\n")
        recursivelyBuildTask(id, spec.value, s)
        s.append("\t}\n")
      }
    }

    
      
    for (task <- tasks.values) {
      val taskID = toGraphvizID(task)
      
      s.append("\tsubgraph cluster_").append(task.name).append(" {\n")
      s.append("\t\t\"").append(taskID).append("""" [margin="0.01,0.01", height=0.0, width=0.0, fontcolor=white, fillcolor=orange, color=orange, style="filled,rounded", label="""").append(task.name).append("\"]\n")
      
      for (input <- task.inputs) {
        val inputID = input.name + "@" + taskID 
        s.append("\t\t\"").append(inputID).append("""" [margin="0.01,0.01", height=0.0, width=0.0, fontcolor=black, fillcolor=white, color=black, style=filled, label="""").append(input.name).append("\"]\n")
        s.append("\t\t\"").append(inputID).append("\" -> \"").append(taskID).append("\"\n")
        recursivelyBuildTask(inputID, input.value, s)
      }

      for (param <- task.params) {
        val paramID = param.name + "@" + taskID
        s.append("\t\t\"").append(paramID).append("""" [margin="0.01,0.01", height=0.0, width=0.0, fontcolor=black, fillcolor=white, color=black, style=filled, shape="box", label="""").append(param.name).append("\"]\n")
        s.append("\t\t\"").append(paramID).append("\" -> \"").append(taskID).append("\"\n")
        recursivelyBuildTask(paramID, param.value, s)
      }

      for (output <- task.outputs) {
        val outputID = output.name + "@" + taskID
        s.append("\t\t\"").append(outputID).append("""" [margin="0.01,0.01", height=0.0, width=0.0, fontcolor=black, fillcolor=white, color=black, style=filled, label="""").append(output.name).append("\"]\n")
        s.append("\t\t\"").append(taskID).append("\" -> \"").append(outputID).append("\"\n")
      }
      
      s.append("\t\tcolor=black\n\t\tfillcolor=white\n\t\tstyle=filled\n")
      s.append("\t}\n\n")
    }  
    
    for (task <- tasks.values) {
      val taskID = toGraphvizID(task)
      
      for (input <- task.inputs) {
        val inputID = input.name + "@" + taskID 
        recursivelyConnect(inputID, input.value, s)
      }

      for (param <- task.params) {
        val paramID = param.name + "@" + taskID
        recursivelyConnect(paramID, param.value, s)
      }     
    }
    
    
    s ++= "}\n"
    
    return s.result
  }
  
  def build(confSpecs: Seq[ast.ConfigAssignment], branchFactory:BranchFactory) : Map[String,Global] = {
    
    val globals = confSpecs.map{ confSpec => Global(recursivelyProcessSpec(confSpec.spec, branchFactory)) }
    
    val tuples = globals.map{ global => 
      global.value.name -> global
    }
    
    val globalMap = tuples.toMap
    
    return globalMap
  }
  
  def build(workflow:ast.WorkflowDefinition, branchFactory:BranchFactory) : Map[String,Task] = {
    
    val taskList:Seq[ast.TaskDef] = 
      (workflow.tasks ++ workflow.functionCallTasks)                  // gather all TaskDef objects from the workflow definition
    
    val taskNameTuples:Seq[(String, Task)] = taskList.map{ taskDef => // for each TaskDef in taskList:
      val task:Task   = build(taskDef, branchFactory)                 //   build a sub-graph (of type Task)
      val name:String = task.name                                     //   get the name of the TaskDef; for the moment we ignore namespaces (XXX: ideally fix this) 
      (name, task)                                                    //   return a tuple of type (String, Task) 
    }
    
    val taskMap:Map[String, Task] = taskNameTuples.toMap              // convert the sequence of tuples into a map, where the keys are task names and the values are Task graph objects
    
    return taskMap
  }
  
  def build(taskDef:ast.TaskDef, branchFactory:BranchFactory) : Task = {
    
    val inputs   = taskDef.inputs.map  { spec => recursivelyProcessSpec(spec, branchFactory) }
    val params   = taskDef.params.map  { spec => recursivelyProcessSpec(spec, branchFactory) }
    val outputs  = taskDef.outputs.map { spec => recursivelyProcessSpec(spec, branchFactory) }
    val packages = taskDef.packages.map{ spec => recursivelyProcessSpec(spec, branchFactory) }
    val code     = taskDef.commands
    val name     = taskDef.name.name                                  //   get the name of the TaskDef; for the moment we ignore namespaces (XXX: ideally fix this) 
    
    return new Task(name, code, inputs, params, outputs, packages)
  }
  
  def build(specs:Seq[ast.Spec], branchFactory:BranchFactory) : Seq[Spec] = {
    return specs.map{ spec => recursivelyProcessSpec(spec, branchFactory) }
  }
  
  
  def processGraftReference(branchGraftElements: Seq[ast.BranchGraftElement], branchFactory:BranchFactory) : Seq[Grafts] = {

    import scala.collection.immutable.Map
    
    val branches = branchGraftElements.flatMap { graftElement => 
      if (graftElement.branchName == "*") {
        branchFactory.getAll(graftElement.branchPointName)
      } else {
        Seq(branchFactory(graftElement.branchName, graftElement.branchPointName))
      }
    }    

    val groups:Map[BranchPoint, Seq[Branch]] = branches.groupBy{ branch => branch.branchPoint }
    
    return Realization.crossProduct(groups).map{ realization => Grafts(realization.branches) }

  }
  
  def recursivelyProcessSpec(spec:ast.Spec, branchFactory:BranchFactory) : Spec = {
    val name = spec.name
    
    val value:ValueBearingNode = spec.rval match {
      case astNode:ast.Unbound => Literal(name)
      case astNode:ast.Literal => Literal(astNode.value)
      case astNode:ast.ConfigVariable          => Reference(astNode.value, None,                   Seq())
      case astNode:ast.ShorthandConfigVariable => Reference(name,          None,                   Seq())
      case astNode:ast.TaskVariable            => Reference(astNode.value, Some(astNode.taskName), Seq())
      case astNode:ast.ShorthandTaskVariable   => Reference(name,          Some(astNode.taskName), Seq())
      case astNode:ast.BranchGraft             => { 
        
        val grafts = processGraftReference(astNode.branchGraftElements, branchFactory)
        
        val reference = astNode.taskName match {
          case Some(taskName) => Reference(astNode.variableName, Some(taskName), grafts)
          case None           => Reference(astNode.variableName, None,           grafts)
        }
        
        reference
      }
      case astNode:ast.ShorthandBranchGraft             => { 
        
        val grafts = processGraftReference(astNode.branchGraftElements, branchFactory)
        
        val reference = Reference(name, Some(astNode.taskName), grafts)
        
        reference
      }
      case astNode:ast.SequentialBranchPoint   => { 
      
        val branchPoint:BranchPoint = astNode.branchPointName match {
          case Some(bp) => branchFactory.getBranchPoint(bp)
          case None     => throw new ducttape.syntax.FileFormatException("Branch point name is required", astNode)
        }
        
        val branches = Seq.newBuilder[BranchNode]
        val sequence = astNode.sequence
        for ( number <- sequence.start to sequence.end by sequence.increment) {
          val value = number.toString
          val literal = Literal(value)
          val branch = branchFactory(value, branchPoint)
          val branchNode = BranchNode(branch, literal)
          
          branches += branchNode
        }
        
       BranchPointNode(branchPoint, branches.result)
        
      }
      case astNode:ast.BranchPointDef          => {
        
        val branchPoint:BranchPoint = astNode.name match {
          case Some(bp) => branchFactory.getBranchPoint(bp)
          case None     => throw new ducttape.syntax.FileFormatException("Branch point name is required", astNode)
        }
        
        val branches = astNode.specs.map{ spec => 
          
          val specNode = recursivelyProcessSpec(spec, branchFactory)
          
          val branchName = spec.name
          val branch = branchFactory(branchName, branchPoint)
          
          BranchNode(branch, specNode.value)
          
        }
        
        BranchPointNode(branchPoint, branches)
      }

    }
    
    return Spec(name, value)
  }
 
  
}
