package interlinked.util

import interlinked.util.AsyncLlmClient

import java.io.OutputStream
import scala.collection.mutable.Queue
import scala.concurrent.ExecutionContext
import scala.concurrent.Future


class MockLlmClient(private val responses: AsyncLlmClient.LlmResponse*)
    extends AsyncLlmClient:
  private val remaining = Queue[AsyncLlmClient.LlmResponse]()
  remaining ++= responses

  def callModel(
      prompt: String,
      responseFormat: Option[AsyncLlmClient.ResponseFormat],
      streamTo: Option[OutputStream]
  ): Future[AsyncLlmClient.LlmResponse] =
    synchronized:
      if remaining.isEmpty then
        throw new RuntimeException(
          "MockLlmClient: no more canned responses available."
        )
      val next = remaining.dequeue()
      Future.successful(next)

  def assertAllConsumed(): Unit =
    synchronized:
      if remaining.nonEmpty then
        throw new RuntimeException(
          s"MockLlmClient: ${remaining.size} response(s) not consumed."
        )
