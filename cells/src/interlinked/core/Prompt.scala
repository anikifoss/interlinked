package interlinked.core

import com.github.mustachejava.Mustache
import interlinked.util.MustacheTemplate


case class PromptTemplate(template: String):
  private val compile: MustacheTemplate = interlinked.util.MustacheCompiler.compile(template)
  def render(attributes: Map[String, Any]): String = compile.render(attributes)


case class UserPrompt(context: String, task: String)
