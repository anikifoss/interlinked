package interlinked.util

import java.io.OutputStream
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait AsyncLlmClient:
  def callModel(
      prompt: String,
      responseFormat: Option[AsyncLlmClient.ResponseFormat] = None,
      streamTo: Option[OutputStream] = None
  ): Future[AsyncLlmClient.LlmResponse]

object AsyncLlmClient:
  enum ResponseFormat(val payloadValue: String):
    case JsonObject extends ResponseFormat("json_object")

  case class LlmResponse(fullText: String):
    lazy val (think: String, result: String) = splitThinkResult()

    def maybeJson: String =
      val firstBrace = result.indexOf('{')
      val lastBrace = result.lastIndexOf('}')
      if firstBrace != -1 && lastBrace != -1 && lastBrace >= firstBrace then
        result.substring(firstBrace, lastBrace + 1)
      else ""

    private def splitThinkResult(): (String, String) =
      val thinkStart = fullText.indexOf("<think>")
      val thinkEnd = fullText.indexOf("</think>", thinkStart + 1)
      
      if thinkStart == -1 || thinkEnd == -1 then
        ("", fullText)
      else
        val thinkContents = fullText.substring(thinkStart + "<think>".length, thinkEnd)
        val resultContents = fullText.substring(thinkEnd + "</think>".length)
        (thinkContents, resultContents)
  end LlmResponse

  private val logger = LoggerFactory.getLogger(this.getClass.getName)
  private val MaxConcurrentRequests =
    Option(System.getenv("INTERLINKED_MAX_HTTP_REQUESTS"))
      .flatMap(_.toIntOption)
      .getOrElse(100)

  private given ExecutionContext =
    ExecutionContext.fromExecutor(
      new ThreadPoolExecutor(
        1,
        MaxConcurrentRequests,
        60L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue[Runnable](),
        new ThreadFactory {
          def newThread(r: Runnable): Thread = {
            val t = new Thread(r, "AsyncLlmClient-Thread")
            t.setDaemon(true)
            t.setUncaughtExceptionHandler: (thread, ex) =>
              logger.error(s"Uncaught exception in thread ${thread.getName}.", ex)
            t
          }
        }
      )
    )

  def apply(
      baseUrl: String,
      apiKey: Option[String] = None,
      model: Option[String] = None,
      connectTimeoutMs: Int = 30000,
      readTimeoutMs: Int = 300000
  ): AsyncLlmClient =
    new AsyncLlmClient:
      val underlying = new SimpleLlmClient(
        baseUrl,
        apiKey,
        model,
        connectTimeoutMs,
        readTimeoutMs
      )

      def callModel(
          prompt: String,
          responseFormat: Option[AsyncLlmClient.ResponseFormat],
          streamTo: Option[OutputStream]
      ): Future[LlmResponse] =
        // Keeping it simple until this becomes an issue.
        Future:
          val fullText = underlying.callModel(
            prompt,
            responseFormat.map(_.payloadValue),
            streamTo
          )
          LlmResponse(fullText)
