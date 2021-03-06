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

package groovyx.gpars.dataflow.stream;

import groovy.lang.Closure;
import groovyx.gpars.actor.impl.MessageStream;
import groovyx.gpars.dataflow.DataCallback;
import groovyx.gpars.dataflow.Dataflow;
import groovyx.gpars.dataflow.DataflowExpression;
import groovyx.gpars.dataflow.DataflowReadChannel;
import groovyx.gpars.dataflow.DataflowVariable;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a deterministic dataflow channel. Unlike a DataflowQueue, DataflowStream allows multiple readers each to read all the messages.
 * Essentially, you may think of DataflowStream as a 1 to many communication channel, since when a reader consumes a messages,
 * other readers will still be able to read the message. Also, all messages arrive to all readers in the same order.
 * DataflowStream is implemented as a functional queue, which impacts the API in that users have to traverse the values in the stream themselves.
 * On the other hand in offers handy methods for value filtering or transformation together with interesting performance characteristics.
 * For convenience and for the ability to use DataflowStream with other dataflow constructs, like e.g. operators,
 * you can wrap DataflowStreams with DataflowReadAdapter for read access or DataflowWriteAdapter for write access.
 * <p/>
 * The DataflowStream class is designed for single-threaded producers and consumers. If multiple threads are supposed to read or write values
 * to the stream, their access to the stream must be serialized externally or the adapters should be used.
 *
 * @param <T> Type for values to pass through the stream
 * @author Johannes Link, Vaclav Pech
 */
@SuppressWarnings({"rawtypes", "TailRecursion", "unchecked", "StaticMethodNamingConvention", "ClassWithTooManyMethods"})
public final class DataflowStream<T> implements FList<T> {

    private final DataflowVariable<T> first = new DataflowVariable<T>();
    private final AtomicReference<DataflowStream<T>> rest = new AtomicReference<DataflowStream<T>>();

    /**
     * A collection of listeners who need to be informed each time the stream is bound to a value
     */
    private final Collection<MessageStream> wheneverBoundListeners;

    public static <T> T eos() {
        return null;
    }

    private static <T> T eval(final Object valueOrDataflowVariable) {
        if (valueOrDataflowVariable instanceof DataflowVariable)
            try {
                return ((DataflowReadChannel<T>) valueOrDataflowVariable).getVal();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        return (T) valueOrDataflowVariable;
    }

    /**
     * Creates an empty stream
     */
    public DataflowStream() {
        wheneverBoundListeners = new CopyOnWriteArrayList<MessageStream>();
    }

    /**
     * Creates a stream while applying the supplied initialization closure to it
     *
     * @param toBeApplied The closure to use for initialization
     */
    public DataflowStream(final Closure toBeApplied) {
        this();
        apply(toBeApplied);
    }

    @SuppressWarnings({"AssignmentToCollectionOrArrayFieldFromParameter"})
    private DataflowStream(final Collection<MessageStream> wheneverBoundListeners) {
        this.wheneverBoundListeners = wheneverBoundListeners;
        hookWheneverBoundListeners(first);
    }

    /**
     * Populates the stream with generated values
     *
     * @param seed      The initial element to evaluate and add as the first value of the stream
     * @param generator A closure generating stream elements from the previous values
     * @param condition A closure indicating whether the generation should continue based on the last generated value
     * @return This stream
     */
    public DataflowStream<T> generate(final T seed, final Closure generator, final Closure condition) {
        generateNext(seed, this, generator, condition);
        return this;
    }

    private void generateNext(final T value, final DataflowStream<T> stream, final Closure generator, final Closure condition) {
        T recurValue = value;
        DataflowStream<T> recurStream = stream;
        while (true) {
            final boolean addValue = (Boolean) condition.call(new Object[]{recurValue});
            if (!addValue) {
                recurStream.leftShift(DataflowStream.<T>eos());
                return;
            }
            recurStream = recurStream.leftShift(recurValue);
            recurValue = (T) eval(generator.call(new Object[]{recurValue}));
        }
    }

    /**
     * Calls the supplied closure with the stream as a parameter
     *
     * @param closure The closure to call
     * @return This instance of DataflowStream
     */
    public final DataflowStream<T> apply(final Closure closure) {
        closure.call(new Object[]{this});
        return this;
    }

    /**
     * Adds a dataflow variable value to the stream, once the value is available
     *
     * @param ref The DataflowVariable to check for value
     * @return The rest of the stream
     */
    public DataflowStream<T> leftShift(final DataflowReadChannel<T> ref) {
        ref.getValAsync(new MessageStream() {
            @Override
            public MessageStream send(final Object message) {
                first.bind((T) message);
                return null;
            }
        });
        return (DataflowStream<T>) getRest();
    }

    /**
     * Adds a value to the stream
     *
     * @param value The value to add
     * @return The rest of the stream
     */
    public DataflowStream<T> leftShift(final T value) {
        bind(value);
        return (DataflowStream<T>) getRest();
    }

    private void bind(final T value) {
        first.bind(value);
    }

    DataflowVariable<T> getFirstDFV() {
        return first;
    }

    /**
     * Retrieved the first element in the stream, blocking until a value is available
     *
     * @return The first item in the stream
     */
    @Override
    public T getFirst() {
        try {
            return first.getVal();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieves a DataflowStream representing the rest of this Stream after removing the first element
     *
     * @return The remaining stream elements
     */
    @Override
    public FList<T> getRest() {
        if (rest.get() == null)
            rest.compareAndSet(null, new DataflowStream<T>(wheneverBoundListeners));
        return rest.get();
    }

    /**
     * Indicates, whether the first element in the stream is an eos
     */
    @Override
    public boolean isEmpty() {
        return getFirst() == eos();
    }

    /**
     * Builds a filtered stream using the supplied filter closure
     *
     * @param filterClosure The closure to decide on inclusion of elements
     * @return The first item of the filtered stream
     */
    @Override
    public FList<T> filter(final Closure filterClosure) {
        final DataflowStream<T> newStream = new DataflowStream<T>();
        filter(this, filterClosure, newStream);
        return newStream;
    }

    private void filter(final DataflowStream<T> rest, final Closure filterClosure, final DataflowStream<T> result) {
        DataflowStream<T> recurRest = rest;
        DataflowStream<T> recurResult = result;
        while (true) {
            if (recurRest.isEmpty()) {
                recurResult.leftShift(DataflowStream.<T>eos());
                return;
            }
            final boolean include = (Boolean) eval(filterClosure.call(new Object[]{recurRest.getFirst()}));
            if (include) recurResult = recurResult.leftShift(recurRest.getFirst());
            recurRest = (DataflowStream<T>) recurRest.getRest();
        }
    }

    /**
     * Builds a modified stream using the supplied map closure
     *
     * @param mapClosure The closure to transform elements
     * @return The first item of the transformed stream
     */
    @Override
    public FList<Object> map(final Closure mapClosure) {
        final DataflowStream<Object> newStream = new DataflowStream<Object>();
        map(this, mapClosure, newStream);
        return newStream;
    }

    private void map(final FList<T> rest, final Closure mapClosure, final DataflowStream result) {
        FList<T> recurRest = rest;
        DataflowStream recurResult = result;
        while (true) {
            if (recurRest.isEmpty()) {
                recurResult.leftShift(DataflowStream.eos());
                return;
            }
            final Object mapped = mapClosure.call(new Object[]{recurRest.getFirst()});
            recurResult = recurResult.leftShift(eval(mapped));
            recurRest = recurRest.getRest();
        }
    }

    /**
     * Reduces all elements in the stream using the supplied closure
     *
     * @param reduceClosure The closure to reduce elements of the stream gradually into an accumulator. The accumulator is seeded with the first stream element.
     * @return The result of reduction of the whole stream
     */
    @Override
    public T reduce(final Closure reduceClosure) {
        if (isEmpty())
            return null;
        return reduce(getFirst(), getRest(), reduceClosure);
    }

    /**
     * Reduces all elements in the stream using the supplied closure
     *
     * @param reduceClosure The closure to reduce elements of the stream gradually into an accumulator.
     * @param seed          The value to initialize the accumulator with.
     * @return The result of reduction of the whole stream
     */
    @Override
    public T reduce(final T seed, final Closure reduceClosure) {
        return reduce(seed, this, reduceClosure);
    }

    private T reduce(final T current, final FList<T> rest, final Closure reduceClosure) {
        T recurCurrent = current;
        FList<T> recurRest = rest;
        while (true) {
            if (recurRest.isEmpty())
                return recurCurrent;
            final Object aggregate = reduceClosure.call(new Object[]{recurCurrent, recurRest.getFirst()});
            recurCurrent = (T) eval(aggregate);
            recurRest = recurRest.getRest();
        }
    }

    /**
     * Builds an iterator to iterate over the stream
     *
     * @return A new FListIterator instance
     */
    @Override
    public Iterator<T> iterator() {
        return new FListIterator<T>(this);
    }

    @Override
    public String toString() {
        if (!first.isBound())
            return "DataflowStream[?]";
        if (isEmpty())
            return "DataflowStream[]";
        return "DataflowStream[" + getFirst() + getRest().appendingString() + ']';
    }

    @Override
    public String appendingString() {
        if (!first.isBound())
            return ", ?";
        if (isEmpty())
            return "";
        return ", " + getFirst() + getRest().appendingString();
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        final FList stream = (FList) obj;
        if (isEmpty())
            return stream.isEmpty();
        if (!getFirst().equals(stream.getFirst()))
            return false;
        return getRest().equals(stream.getRest());
    }

    @Override
    public int hashCode() {
        int result = first.hashCode();
        result = 31 * result + rest.hashCode();
        return result;
    }

    public void wheneverBound(final Closure closure) {
        wheneverBoundListeners.add(new DataCallback(closure, Dataflow.retrieveCurrentDFPGroup()));
        first.whenBound(closure);
    }

    public void wheneverBound(final MessageStream stream) {
        wheneverBoundListeners.add(stream);
        first.whenBound(stream);
    }

    /**
     * Hooks the registered when bound handlers to the supplied dataflow expression
     *
     * @param expr The expression to hook all the when bound listeners to
     * @return The supplied expression handler to allow method chaining
     */
    private DataflowExpression<T> hookWheneverBoundListeners(final DataflowExpression<T> expr) {
        for (final MessageStream listener : wheneverBoundListeners) {
            expr.whenBound(listener);
        }
        return expr;
    }
}

