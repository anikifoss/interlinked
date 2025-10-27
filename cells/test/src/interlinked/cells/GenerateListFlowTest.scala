package interlinked.cells

import com.rallyhealth.weejson.v1.jackson.*
import com.rallyhealth.weepickle.v1.WeePickle.*
import interlinked.cells.GenerateListFlow.ListResponse
import interlinked.core.*
import interlinked.util.*
import interlinked.util.AsyncLlmClient.LlmResponse
import interlinked.util.MockLlmClient
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.*
import utest.*

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*


object GenerateListFlowTest extends TestSuite:
  given system: ActorSystem = ActorSystem("GenerateListFlowTest")
  import system.dispatcher

  override def utestAfterAll(): Unit =
    system.terminate()

  val tests = Tests:
    test("returns an empty list when LLM immediately says nothing to add"):
      val client = MockLlmClient(
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.NoMoreItemsStatus,
            List()
          )
        )
      )
      val flow = GenerateListFlow(client, maxAttempts = 3)

      val result = runFlowWithPrompt(flow, "List prime numbers")
      assert(result.isEmpty)
      client.assertAllConsumed()

    test("returns concatenated items after multiple successful additions"):
      val client = MockLlmClient(
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.AddedItemsStatus,
            List(
              GenerateListFlow.ListItem("A", "A-details")
            )
          )
        ),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.AddedItemsStatus,
            List(
              GenerateListFlow.ListItem("B", "B-details")
            )
          )
        ),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.NoMoreItemsStatus,
            List()
          )
        )
      )
      val flow = GenerateListFlow(client, maxAttempts = 3)

      val result = runFlowWithPrompt(flow, "List letters")
      val expected = List(
        GenerateListFlow.ListItem("A", "A-details"),
        GenerateListFlow.ListItem("B", "B-details")
      )
      assert(result == expected)
      client.assertAllConsumed()

    test("deduplicates items with same name"):
      val client = MockLlmClient(
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.AddedItemsStatus,
            List(
              GenerateListFlow.ListItem("A", "A-details")
            )
          )
        ),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.AddedItemsStatus,
            List(
              GenerateListFlow.ListItem("A", "A-details-again")
            )
          )
        ),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.NoMoreItemsStatus,
            List()
          )
        )
      )
      val flow = GenerateListFlow(client, maxAttempts = 3)

      val result = runFlowWithPrompt(flow, "List letters")
      val expected = List(
        GenerateListFlow.ListItem("A", "A-details")
      )
      assert(result == expected)
      client.assertAllConsumed()

    test("retries when LLM returns inconsistent status once"):
      val client = MockLlmClient(
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.AddedItemsStatus,
            List()
          )
        ), // inconsistent
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.NoMoreItemsStatus,
            List()
          )
        )
      )
      val flow = GenerateListFlow(client, maxAttempts = 3)

      val result = runFlowWithPrompt(flow, "List letters")
      assert(result.isEmpty)
      client.assertAllConsumed()

    test("fails after exhausting maxAttempts on inconsistent responses"):
      val client = MockLlmClient(
        List.fill(3)(
          // Always inconsistent.
          mkLlmResponse(
            ListResponse(
              GenerateListFlow.AddedItemsStatus,
              List()
            )
          )
        )*
      )
      val flow = GenerateListFlow(client, maxAttempts = 3)

      val ex = assertThrows[RuntimeException]:
        runFlowWithPrompt(flow, "List letters")

      assert(ex.getMessage.contains("Inconsistent LLM responses exhausted 3 attempts"))
      client.assertAllConsumed()

    test("retries on transient client failure"):
      val client = MockLlmClient(
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.AddedItemsStatus,
            List(
              GenerateListFlow.ListItem("X", "X-details")
            )
          )
        ),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.NoMoreItemsStatus,
            List()
          )
        )
      )
      // inject failure by overriding callModel
      var calls = 0
      val failingClient = new AsyncLlmClient:
        def callModel(
            prompt: String,
            responseFormat: Option[AsyncLlmClient.ResponseFormat],
            streamTo: Option[java.io.OutputStream]
        ) =
          calls += 1
          if calls == 1 then Future.failed(new RuntimeException("boom"))
          else client.callModel(prompt, responseFormat, streamTo)
      
      val flow = GenerateListFlow(failingClient, maxAttempts = 3)

      val result = runFlowWithPrompt(flow, "List letters")
      val expected = List(
        GenerateListFlow.ListItem("X", "X-details")
      )
      assert(result == expected)

    test("fails when client keeps failing"):
      val client = new AsyncLlmClient:
        def callModel(
            prompt: String,
            responseFormat: Option[AsyncLlmClient.ResponseFormat],
            streamTo: Option[java.io.OutputStream]
        ) =
          Future.failed(new RuntimeException("persistent failure"))

      val flow = GenerateListFlow(client, maxAttempts = 2)

      val ex = assertThrows[RuntimeException]:
        runFlowWithPrompt(flow, "List letters")

      assert(ex.getMessage.contains("persistent failure"))

    test("handles malformed JSON gracefully by retrying"):
      val client = MockLlmClient(
        LlmResponse("not json"),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.NoMoreItemsStatus,
            List()
          )
        )
      )
      val flow = GenerateListFlow(client, maxAttempts = 3)

      val result = runFlowWithPrompt(flow, "List letters")
      assert(result.isEmpty)
      client.assertAllConsumed()

    test("handles missing fields by retrying"):
      val client = MockLlmClient(
        LlmResponse(
          """{"response_status":"added_new_items"}"""
        ),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.NoMoreItemsStatus,
            List()
          )
        )
      )
      val flow = GenerateListFlow(client, maxAttempts = 3)

      val result = runFlowWithPrompt(flow, "List letters")
      assert(result.isEmpty)
      client.assertAllConsumed()

    test("handles empty JSON string"):
      val client = MockLlmClient(
        LlmResponse(""),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.NoMoreItemsStatus,
            List()
          )
        )
      )
      val flow = GenerateListFlow(client, maxAttempts = 3)

      val result = runFlowWithPrompt(flow, "List letters")
      assert(result.isEmpty)
      client.assertAllConsumed()

    test("handles incorrect status while keeping results"):
      val client = MockLlmClient(
        mkLlmResponse(
          ListResponse(
            "this_status_is_incorrect",
            List(
              GenerateListFlow.ListItem("A", "A-details")
            )
          )
        ),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.AddedItemsStatus,
            List(
              GenerateListFlow.ListItem("B", "B-details")
            )
          )
        ),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.NoMoreItemsStatus,
            List()
          )
        )
      )
      val flow = GenerateListFlow(client, maxAttempts = 3)

      val result = runFlowWithPrompt(flow, "List letters")
      val expected = List(
        GenerateListFlow.ListItem("A", "A-details"),
        GenerateListFlow.ListItem("B", "B-details")
      )
      assert(result == expected)
      client.assertAllConsumed()

    test("handles nested retries"):
      val client = MockLlmClient(
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.AddedItemsStatus,
            List(
              GenerateListFlow.ListItem("A", "A-details")
            )
          )
        ),
        LlmResponse("malformed"),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.AddedItemsStatus,
            List(
              GenerateListFlow.ListItem("B", "B-details")
            )
          )
        ),
        mkLlmResponse(
          ListResponse(
            GenerateListFlow.NoMoreItemsStatus,
            List()
          )
        )
      )
      val flow = GenerateListFlow(client, maxAttempts = 3)

      val result = runFlowWithPrompt(flow, "List letters")
      val expected = List(
        GenerateListFlow.ListItem("A", "A-details"),
        GenerateListFlow.ListItem("B", "B-details")
      )
      assert(result == expected)
      client.assertAllConsumed()

    test("fails on JSON parsing exception after retries"):
      val client = MockLlmClient(
        List.fill(2)(
          LlmResponse("not json")
        )*
      )
      val flow = GenerateListFlow(client, maxAttempts = 2)

      assertThrows[Exception]:
        runFlowWithPrompt(flow, "List letters")

      client.assertAllConsumed()
  end tests

  private def mkLlmResponse(r: ListResponse): LlmResponse =
    LlmResponse(FromScala(r).transform(ToJson.string))

  private def runFlowWithPrompt(
      flow: Flow[UserPrompt, List[GenerateListFlow.ListItem], NotUsed],
      userTask: String
  ): List[GenerateListFlow.ListItem] =
    val future = Source
      .single(UserPrompt(context = "", task = userTask))
      .via(flow)
      .runWith(Sink.seq)
    Await.result(future, 5.seconds).flatten.toList
