// GPars - Groovy Parallel Systems
//
// Copyright © 2008-11  The original author or authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package groovyx.gpars.dataflow.operator

import groovyx.gpars.dataflow.Dataflow
import groovyx.gpars.dataflow.DataflowQueue
import groovyx.gpars.dataflow.DataflowVariable
import groovyx.gpars.group.DefaultPGroup
import groovyx.gpars.group.PGroup
import java.util.concurrent.TimeUnit

/**
 * @author Vaclav Pech
 * Date: Sep 9, 2009
 */

public class DataflowOperatorTest extends GroovyTestCase {

    private PGroup group

    protected void setUp() {
        group = new DefaultPGroup(1)
        super.setUp()
    }

    protected void tearDown() {
        group.shutdown()
        super.tearDown()
    }

    public void testOperator() {
        final DataflowVariable a = new DataflowVariable()
        final DataflowVariable b = new DataflowVariable()
        final DataflowQueue c = new DataflowQueue()
        final DataflowVariable d = new DataflowVariable()
        final DataflowQueue e = new DataflowQueue()

        def op = group.operator(inputs: [a, b, c], outputs: [d, e]) {x, y, z ->
            bindOutput 0, x + y + z
            bindOutput 1, x * y * z
        }

        Dataflow.task { a << 5 }
        Dataflow.task { b << 20 }
        Dataflow.task { c << 40 }

        assertEquals 65, d.val
        assertEquals 4000, e.val

        op.stop()
    }

    public void testOperatorWithDoubleWaitOnChannel() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()

        def op = group.operator(inputs: [a, a], outputs: [b]) {x, y ->
            bindOutput 0, x + y
        }

        a << 1
        a << 2
        a << 3
        a << 4

        assertEquals 3, b.val
        assertEquals 7, b.val

        op.stop()
    }

    public void testNonCommutativeOperator() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()

        def op = group.operator(inputs: [a, b], outputs: [c]) {x, y ->
            bindOutput 0, 2 * x + y
        }

        Dataflow.task { a << 5 }
        Dataflow.task { b << 20 }

        assertEquals 30, c.val

        op.stop()
    }

    public void testGroupingOperatorsAndTasks() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()

        def op = group.operator(inputs: [a, b], outputs: [c]) {x, y ->
            bindOutput 0, 2 * x + y
        }

        group.task { a << 5 }
        group.task { b << 20 }

        assertEquals 30, c.val

        op.stop()
    }

    public void testSimpleOperators() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()
        a << 1
        a << 2
        a << 3
        a << 4
        a << 5

        def op1 = group.operator(inputs: [a], outputs: [b]) {v ->
            bindOutput 2 * v
        }

        def op2 = group.operator(inputs: [b], outputs: [c]) {v ->
            bindOutput v + 1
        }
        assertEquals 3, c.val
        assertEquals 5, c.val
        assertEquals 7, c.val
        assertEquals 9, c.val
        assertEquals 11, c.val
        [op1, op2]*.stop()
    }

    public void testCombinedOperators() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()
        a << 1
        a << 2
        a << 3
        b << 4
        b << 5
        b << 6
        b << 7

        final DataflowQueue x = new DataflowQueue()
        def op1 = group.operator(inputs: [a], outputs: [x]) {v ->
            bindOutput v * v
        }

        final DataflowQueue y = new DataflowQueue()
        def op2 = group.operator(inputs: [b], outputs: [y]) {v ->
            bindOutput v * v
        }

        def op3 = group.operator(inputs: [x, y], outputs: [c]) {v1, v2 ->
            bindOutput v1 + v2
        }

        assertEquals 17, c.val
        assertEquals 29, c.val
        assertEquals 45, c.val
        [op1, op2, op3]*.stop()
    }

    public void testStop() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()
        volatile boolean flag = false

        def op1 = group.operator(inputs: [a, b], outputs: [c]) {x, y ->
            flag = true
        }
        a << 'Never delivered'
        op1.stop()
        op1.join()
        assertFalse flag
    }

    public void testInterrupt() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        volatile boolean flag = false

        def op1 = group.operator(inputs: [a], outputs: [b]) {v ->
            Thread.currentThread().interrupt()
            flag = true
            bindOutput 'a'
        }
        op1.actor.metaClass.onInterrupt = {}
        assertFalse flag
        a << 'Message'
        assertEquals 'a', b.val
        assertTrue flag
        op1.stop()
        op1.join()
    }

    public void testEmptyInputs() {
        final DataflowQueue b = new DataflowQueue()
        volatile boolean flag = false

        shouldFail(IllegalArgumentException) {
            def op1 = group.operator(inputs: [], outputs: [b]) {->
                flag = true
                stop()
            }
            op1.join()
        }
        assert !flag
    }

    public void testOutputs() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()
        volatile boolean flag = false

        def op1 = group.operator(inputs: [a], outputs: [b, c]) {
            flag = (output == b) && (outputs[0] == b) && (outputs[1] == c)
            stop()
        }
        a << null
        op1.join()
        assert flag
        assert (op1.output == b) && (op1.outputs[0] == b) && (op1.outputs[1] == c)
        assert (op1.getOutput() == b) && (op1.getOutputs(0) == b) && (op1.getOutputs(1) == c)
    }

    public void testEmptyOutputs() {
        final DataflowQueue b = new DataflowQueue()
        volatile boolean flag = false

        def op1 = group.operator(inputs: [b], outputs: []) {
            flag = (output == null)
            stop()
        }
        b << null
        op1.join()
        assert flag
        assert op1.output == null
    }

    public void testInputNumber() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()
        final DataflowQueue d = new DataflowQueue()

        group.with {
            shouldFail(IllegalArgumentException) {
                operator(inputs: [a, b, c], outputs: [d]) {v -> }
            }
            shouldFail(IllegalArgumentException) {
                operator(inputs: [a, b], outputs: [d]) {v -> }
            }
            shouldFail(IllegalArgumentException) {
                operator(inputs: [a, b], outputs: [d]) {x, y, z -> }
            }
            shouldFail(IllegalArgumentException) {
                operator(inputs: [a, b], outputs: [d]) {}
            }
            shouldFail(IllegalArgumentException) {
                operator(inputs: [a], outputs: [d]) {x, y -> }
            }
            shouldFail(IllegalArgumentException) {
                operator(inputs: [], outputs: [d]) { }
            }
            shouldFail(IllegalArgumentException) {
                operator(inputs: [a], outputs: [d]) {-> }
            }
            def op1 = operator(inputs: [a], outputs: [d]) { }
            op1.stop()
            op1 = operator(inputs: [a], outputs: [d]) {x -> }
            op1.stop()
            op1 = operator(inputs: [a, b], outputs: [d]) {x, y -> }
            op1.stop()
        }

    }

    public void testOutputNumber() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue d = new DataflowQueue()

        group.operator(inputs: [a], outputs: []) {v -> stop()}
        group.operator(inputs: [a]) {v -> stop()}
        group.operator(inputs: [a], mistypedOutputs: [d]) {v -> stop()}

        a << 'value'
        a << 'value'
        a << 'value'
    }

    public void testMissingChannels() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue d = new DataflowQueue()

        shouldFail(IllegalArgumentException) {
            group.operator(inputs1: [a], outputs: [d]) {v -> }
        }
        shouldFail(IllegalArgumentException) {
            group.operator(outputs: [d]) {v -> }
        }
        shouldFail(IllegalArgumentException) {
            group.operator([:]) {v -> }
        }
    }

    public void testException() {
        final DataflowQueue stream = new DataflowQueue()
        final DataflowVariable a = new DataflowVariable()

        def op = group.operator(inputs: [stream], outputs: []) {
            throw new RuntimeException('test')
        }
        op.metaClass.reportError = {Throwable e ->
            a << e
            stop()
        }
        stream << 'value'
        assert a.val instanceof RuntimeException
    }

    public void testExceptionWithDefaultHandler() {
        final DataflowQueue stream = new DataflowQueue()

        def op = group.operator(inputs: [stream], outputs: []) {
            if (it == 'invalidValue') throw new RuntimeException('test')
        }
        stream << 'value'
        stream << 'invalidValue'
        op.join()
    }

    public void testBindAllOutputs() {
        def group = new DefaultPGroup(10)
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()
        final DataflowQueue d = new DataflowQueue()

        def op1 = group.operator(inputs: [a], outputs: [b, c, d], maxForks: 5) {x ->
            bindAllOutputs x
        }
        final IntRange range = 1..100
        range.each {a << it}
        def bs = range.collect {b.val}
        def cs = range.collect {c.val}
        def ds = range.collect {d.val}
        assert bs.size() == range.to
        assert cs.size() == range.to
        assert ds.size() == range.to
        op1.stop()
        op1.join()
        group.shutdown()
    }

    public void testBindAllOutputValues() {
        def group = new DefaultPGroup(10)
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()
        final DataflowQueue d = new DataflowQueue()

        def op1 = group.operator(inputs: [a], outputs: [b, c, d], maxForks: 5) {x ->
            bindAllOutputValues x, x, x
        }
        final IntRange range = 1..100
        range.each {a << it}
        def bs = range.collect {b.val}
        def cs = range.collect {c.val}
        def ds = range.collect {d.val}
        assert bs.size() == range.to
        assert cs.size() == range.to
        assert ds.size() == range.to
        op1.stop()
        op1.join()
        group.shutdown()
    }

    public void testBindAllOutputsAtomically() {
        def group = new DefaultPGroup(10)
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()
        final DataflowQueue d = new DataflowQueue()

        def op1 = group.operator(inputs: [a], outputs: [b, c, d], maxForks: 5) {x ->
            bindAllOutputsAtomically x
        }
        final IntRange range = 1..10
        range.each {a << it}
        def bs = range.collect {b.val}
        def cs = range.collect {c.val}
        def ds = range.collect {d.val}
        assert bs.size() == range.to
        assert cs.size() == range.to
        assert ds.size() == range.to
        assert bs == cs
        assert bs == ds
        assert cs == ds
        op1.stop()
        op1.join()
        group.shutdown()
    }

    public void testBindAllOutputValuesAtomically() {
        def group = new DefaultPGroup(10)
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()
        final DataflowQueue d = new DataflowQueue()

        def op1 = group.operator(inputs: [a], outputs: [b, c, d], maxForks: 5) {x ->
            bindAllOutputValuesAtomically x, x, x
        }
        final IntRange range = 1..10
        range.each {a << it}
        def bs = range.collect {b.val}
        def cs = range.collect {c.val}
        def ds = range.collect {d.val}
        assert bs.size() == range.to
        assert cs.size() == range.to
        assert ds.size() == range.to
        assert bs == cs
        assert bs == ds
        assert cs == ds
        op1.stop()
        op1.join()
        group.shutdown()
    }

    public void testBindAllOutputValuesAtomicallyWithDifferentValues() {
        def group = new DefaultPGroup(10)
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()
        final DataflowQueue c = new DataflowQueue()
        final DataflowQueue d = new DataflowQueue()

        def op1 = group.operator(inputs: [a], outputs: [b, c, d], maxForks: 5) {x ->
            bindAllOutputValuesAtomically x, 2 * x, 3 * x
        }
        final IntRange range = 1..10
        range.each {a << it}
        def bs = range.collect {b.val}
        def cs = range.collect {c.val}
        def ds = range.collect {d.val}
        assert bs.size() == range.to
        assert cs.size() == range.to
        assert ds.size() == range.to
        assert cs == bs.collect {2 * it}
        assert ds == bs.collect {3 * it}
        op1.stop()
        op1.join()
        group.shutdown()
    }

    public void testOperatorDoesNotConsumeMessagesAfterStop() {
        final DataflowQueue a = new DataflowQueue()
        final DataflowQueue b = new DataflowQueue()

        group.operator(inputs: [a], outputs: [b]) {
            bindOutput 0, it
            stop()
        }

        a << 1
        a << 2
        a << 3
        a << 4

        assertEquals 1, b.val
        assertNull b.getVal(1, TimeUnit.SECONDS)
    }
}
