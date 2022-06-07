package com.squareup.workflow1.internal

import com.squareup.workflow1.NoopWorkflowInterceptor
import com.squareup.workflow1.Worker
import com.squareup.workflow1.Workflow
import com.squareup.workflow1.WorkflowOutput
import com.squareup.workflow1.action
import com.squareup.workflow1.runningWorker
import com.squareup.workflow1.stateful
import com.squareup.workflow1.stateless
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class WorkflowRunnerTest {

  private val scope = TestScope()

  @AfterTest fun tearDown() {
    scope.cancel()
  }

  @Test fun `initial nextRendering() returns initial rendering`() {
    val workflow = Workflow.stateless<Unit, Nothing, String> { "foo" }
    val runner = WorkflowRunner(workflow, MutableStateFlow(Unit))
    val rendering = runner.nextRendering().rendering
    assertEquals("foo", rendering)
  }

  @Test fun `initial nextRendering() uses initial props`() {
    val workflow = Workflow.stateless<String, Nothing, String> { it }
    val runner = WorkflowRunner(workflow, MutableStateFlow("foo"))
    val rendering = runner.nextRendering().rendering
    assertEquals("foo", rendering)
  }

  @Test fun `initial nextOutput() does not handle initial props`() {
    val workflow = Workflow.stateless<String, Nothing, String> { it }
    val props = MutableStateFlow("initial")
    val runner = WorkflowRunner(workflow, props)
    runner.nextRendering()

    val outputDeferred = scope.async { runner.nextOutput() }

    scope.runCurrent()
    assertTrue(outputDeferred.isActive)
  }

  @Test fun `initial nextOutput() handles props changed after initialization`() {
    val workflow = Workflow.stateless<String, Nothing, String> { it }
    val props = MutableStateFlow("initial")
    // The dispatcher is paused, so the produceIn coroutine won't start yet.
    val runner = WorkflowRunner(workflow, props)
    // The initial value will be read during initialization, so we can change it any time after
    // that.
    props.value = "changed"

    // Get the runner into the state where it's waiting for a props update.
    val initialRendering = runner.nextRendering().rendering
    assertEquals("initial", initialRendering)
    val output = scope.async { runner.nextOutput() }
    assertTrue(output.isActive)

    // Resume the dispatcher to start the coroutines and process the new props value.
    scope.runCurrent()

    assertTrue(output.isCompleted)
    assertNull(output.getCompleted())
    val rendering = runner.nextRendering().rendering
    assertEquals("changed", rendering)
  }

  @Test fun `nextOutput() handles workflow update`() {
    val workflow = Workflow.stateful<Unit, String, String, String>(
      initialState = { "initial" },
      render = { _, renderState ->
        runningWorker(Worker.from { "work" }) {
          action {
            state = "state: $it"
            setOutput("output: $it")
          }
        }
        return@stateful renderState
      }
    )
    val runner = WorkflowRunner(workflow, MutableStateFlow(Unit))

    val initialRendering = runner.nextRendering().rendering
    assertEquals("initial", initialRendering)

    val output = runner.runTillNextOutput()
    assertEquals("output: work", output?.value)

    val updatedRendering = runner.nextRendering().rendering
    assertEquals("state: work", updatedRendering)
  }

  @Test fun `nextOutput() handles concurrent props change and workflow update`() {
    val workflow = Workflow.stateful<String, String, String, String>(
      initialState = { "initial state($it)" },
      render = { renderProps, renderState ->
        runningWorker(Worker.from { "work" }) {
          action {
            state = "state: $it"
            setOutput("output: $it")
          }
        }
        return@stateful "$renderProps|$renderState"
      }
    )
    val props = MutableStateFlow("initial props")
    val runner = WorkflowRunner(workflow, props)
    props.value = "changed props"
    val initialRendering = runner.nextRendering().rendering
    assertEquals("initial props|initial state(initial props)", initialRendering)

    // The order in which props update and workflow update are processed is deterministic, based
    // on the order they appear in the select block in nextOutput.
    val firstOutput = runner.runTillNextOutput()
    // First update will be props, so no output value.
    assertNull(firstOutput)
    val secondRendering = runner.nextRendering().rendering
    assertEquals("changed props|initial state(initial props)", secondRendering)

    val secondOutput = runner.runTillNextOutput()
    assertEquals("output: work", secondOutput?.value)
    val thirdRendering = runner.nextRendering().rendering
    assertEquals("changed props|state: work", thirdRendering)
  }

  @Test fun `cancelRuntime() does not interrupt nextOutput()`() {
    val workflow = Workflow.stateless<Unit, Nothing, Unit> {}
    val runner = WorkflowRunner(workflow, MutableStateFlow(Unit))
    runner.nextRendering()
    val output = scope.async { runner.nextOutput() }
    scope.runCurrent()
    assertTrue(output.isActive)

    // nextOutput is run on the scope passed to the runner, so it shouldn't be affected by this
    // call.
    runner.cancelRuntime()

    scope.advanceUntilIdle()
    assertTrue(output.isActive)
  }

  @Test fun `cancelRuntime() cancels runtime`() {
    var cancellationException: Throwable? = null
    val workflow = Workflow.stateless<Unit, Nothing, Unit> {
      runningSideEffect(key = "test side effect") {
        suspendCancellableCoroutine { continuation ->
          continuation.invokeOnCancellation { cause -> cancellationException = cause }
        }
      }
    }
    val runner = WorkflowRunner(workflow, MutableStateFlow(Unit))
    runner.nextRendering()
    scope.runCurrent()
    assertNull(cancellationException)

    runner.cancelRuntime()

    scope.advanceUntilIdle()
    assertNotNull(cancellationException)
    val causes = generateSequence(cancellationException) { it.cause }
    assertTrue(causes.all { it is CancellationException })
  }

  @Test fun `cancelling scope interrupts nextOutput()`() {
    val workflow = Workflow.stateless<Unit, Nothing, Unit> {}
    val runner = WorkflowRunner(workflow, MutableStateFlow(Unit))
    runner.nextRendering()
    val output = scope.async { runner.nextOutput() }
    scope.runCurrent()
    assertTrue(output.isActive)

    scope.cancel("foo")

    scope.advanceUntilIdle()
    assertTrue(output.isCancelled)
    val realCause = output.getCompletionExceptionOrNull()
    assertEquals("foo", realCause?.message)
  }

  @Test fun `cancelling scope cancels runtime`() {
    var cancellationException: Throwable? = null
    val workflow = Workflow.stateless<Unit, Nothing, Unit> {
      runningSideEffect(key = "test") {
        suspendCancellableCoroutine { continuation ->
          continuation.invokeOnCancellation { cause -> cancellationException = cause }
        }
      }
    }
    val runner = WorkflowRunner(workflow, MutableStateFlow(Unit))
    runner.nextRendering()
    val output = scope.async { runner.nextOutput() }
    scope.runCurrent()
    assertTrue(output.isActive)
    assertNull(cancellationException)

    scope.cancel("foo")

    scope.advanceUntilIdle()
    assertTrue(output.isCancelled)
    assertNotNull(cancellationException)
    assertEquals("foo", cancellationException!!.message)
  }

  private fun <T> WorkflowRunner<*, T, *>.runTillNextOutput(): WorkflowOutput<T>? = scope.run {
    val firstOutputDeferred = async { nextOutput() }
    runCurrent()
    firstOutputDeferred.getCompleted()
  }

  @Suppress("TestFunctionName")
  private fun <P, O : Any, R> WorkflowRunner(
    workflow: Workflow<P, O, R>,
    props: StateFlow<P>
  ): WorkflowRunner<P, O, R> = WorkflowRunner(
    scope, workflow, props, snapshot = null, interceptor = NoopWorkflowInterceptor
  )
}
