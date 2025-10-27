package interlinked

import interlinked.cells.GenerateListFlow
import interlinked.core.UserPrompt
import interlinked.util.AsyncLlmClient
import interlinked.util.AsyncLlmClient.ResponseFormat
import org.apache.pekko.Done
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.scaladsl.RunnableGraph
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration


/* TODO
 * redesign prompt for better prompt caching (variable list at the end)
 * 
 */
@main def main(): Unit =
  generateListExample()


def generateListExample(): Unit =
  given system: ActorSystem = ActorSystem("Cells")

  val client = AsyncLlmClient(
    baseUrl = "http://localhost:8070/v1"
  )
  val userPrompts = List(
    UserPrompt(
      context = """
        |We're creating a shoot-em-up vertical scrolling game with upgrades, bosses, and
        |different levels populated by unique enemies.
        |""".stripMargin,
      task = "Give me a list of requirements for the game."
    ),
  )

  val graph: RunnableGraph[Future[Done]] =
    Source(userPrompts)
      .via(GenerateListFlow(client, maxAttempts = 2))
      .mapConcat(identity)
      .toMat(Sink.foreach(println))(Keep.right)

  val done: Future[Done] = graph.run()
  done.onComplete(_ => system.terminate())
  Await.result(done, Duration.Inf)
