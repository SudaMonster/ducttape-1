package ducttape.exec

import collection._
import ducttape.workflow.Realization
import ducttape.workflow.VersionedTask
import ducttape.syntax.AbstractSyntaxTree.PackageDef
import ducttape.syntax.AbstractSyntaxTree.Spec
import ducttape.syntax.FileFormatException
import java.io.File

// Visits each planned task, discovering what software packages
// will be required to execute it 
class PackageFinder(todo: Option[Set[(String,Realization)]],
                    packageDefs: Map[String,PackageDef]) extends UnpackedDagVisitor {
  
  val packages = new mutable.HashSet[PackageDef]
  
  override def visit(task: VersionedTask) {
    // TODO: Match...
    // TODO: Why do we need todo here? Isn't this enforced by the walker?
    if (todo == None || todo.get( (task.name, task.realization) )) {
      for (packageSpec: Spec <- task.packages) {
        if (packageDefs.contains(packageSpec.name)) {
          packages += packageDefs(packageSpec.name)
        } else {
          // TODO: This should be checked by now...
          throw new FileFormatException("Undefined package %s".format(packageSpec.name), packageSpec)
        }
      }
    }
  }
}
