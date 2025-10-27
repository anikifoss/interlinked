package interlinked.util

import com.github.mustachejava.DefaultMustacheFactory
import com.github.mustachejava.Mustache

import java.io.StringReader
import java.io.StringWriter
import scala.jdk.CollectionConverters.*


object MustacheCompiler:
  private val mf = new DefaultMustacheFactory()

  def compile(template: String): MustacheTemplate =
    val mustache: Mustache = mf.compile(new StringReader(template), "root")
    MustacheTemplate(mustache)


class MustacheTemplate(private val mustache: Mustache):
  def render(attributes: Map[String, Any]): String =
    val writer = new StringWriter()
    mustache.execute(writer, toJava(attributes))
    writer.toString

  private def toJava(attributes: Map[String, Any]): java.util.Map[String, Any] =
    attributes.mapValues:
      case s: Seq[_] =>
        s.map:
          case m: Map[_, _] => toJava(m.asInstanceOf[Map[String, Any]])
          case other => other
        .asJava
      case m: Map[_, _] => toJava(m.asInstanceOf[Map[String, Any]])
      case other => other
    .toMap.asJava
