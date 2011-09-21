package org.nlogo.prim.etc

import org.nlogo.agent.Link
import org.nlogo.api.Syntax
import org.nlogo.nvm.{ Command, Context }

class _hidelink extends Command {
  override def syntax =
    Syntax.commandSyntax("---L", true)
  override def perform(context: Context) {
    context.agent.asInstanceOf[Link].hidden(true)
    context.ip = next
  }
}