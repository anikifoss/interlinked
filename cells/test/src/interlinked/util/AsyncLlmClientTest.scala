package interlinked.util

import cask.*
import interlinked.util.AsyncLlmClient
import io.undertow.Undertow
import utest.*

import java.io.*
import java.net.ServerSocket
import scala.concurrent.Await
import scala.concurrent.duration.*


object AsyncLlmClientTest extends TestSuite:
  val tests = Tests:
    test("callModel returns empty string for empty choices") - withResponse(
      """{"choices": []}"""
    ): url =>
      val client = mkClient(url)
      val res = Await.result(client.callModel("hello"), 5.seconds)
      assert(res.fullText == "")

    test("callModel returns content from single choice") - withResponse(
      """{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""
    ): url =>
      val client = mkClient(url)
      val res = Await.result(client.callModel("hello"), 5.seconds)
      assert(res.fullText == "hi")

    test("callModel streams content") - withResponse(
      """data: {"choices":[{"delta":{"content":"h"}}]}""",
      """data: {"choices":[{"delta":{"content":"i"}}]}""",
      """data: [DONE]"""
    ): url =>
      val out = new ByteArrayOutputStream()
      val client = mkClient(url)
      val res =
        Await.result(client.callModel("hello", streamTo = Some(out)), 5.seconds)
      assert(res.fullText == "hi")
      assert(out.toString == "hi")

    test("LlmResponse think is empty when no think tags"):
      val resp = interlinked.util.AsyncLlmClient.LlmResponse("plain answer")
      assert(resp.think == "")
      assert(resp.result == "plain answer")

    test("LlmResponse think and result split correctly when tags on newlines"):
      val full =
        """<think>
          |reasoning here
          |</think>
          |final answer""".stripMargin
      val resp = interlinked.util.AsyncLlmClient.LlmResponse(full)
      assert(resp.think.trim == "reasoning here")
      assert(resp.result.trim == "final answer")

    test("LlmResponse think and result split correctly when tags are mixed with text"):
      val full =
        """<think>reasoning here</think>final answer""".stripMargin
      val resp = interlinked.util.AsyncLlmClient.LlmResponse(full)
      assert(resp.think == "reasoning here")
      assert(resp.result == "final answer")

    test("LlmResponse maybeJson extracts JSON object"):
      val full =
        """<think>ignore</think>
          |Some text {"a":1} more""".stripMargin
      val resp = interlinked.util.AsyncLlmClient.LlmResponse(full)
      assert(resp.maybeJson == """{"a":1}""")

    test("LlmResponse maybeJson returns empty when no JSON object"):
      val resp = interlinked.util.AsyncLlmClient.LlmResponse("no json here")
      assert(resp.maybeJson == "")

  private class MockHttpServer(chunks: List[String]) extends cask.MainRoutes:
    @cask.post("/v1/chat/completions")
    def handle(request: cask.Request) =
      chunks.mkString("\n")

    initialize()

  private def withResponse[T](chunks: String*)(f: String => T): T =
    val mockServer = MockHttpServer(chunks.toList)
    val host = "localhost"
    val port = 9099
    val server = Undertow.builder
      .addHttpListener(port, host)
      .setHandler(mockServer.defaultHandler)
      .build
    server.start()
    val res =
      try f(s"http://$host:$port/v1")
      finally server.stop()
    res
  end withResponse

  private def mkClient(url: String): AsyncLlmClient =
    AsyncLlmClient(
      baseUrl = url,
      apiKey = None,
      connectTimeoutMs = 1000,
      readTimeoutMs = 1000
    )
