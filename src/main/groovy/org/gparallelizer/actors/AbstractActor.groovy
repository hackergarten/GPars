package org.gparallelizer.actors

import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.gparallelizer.actors.util.EnhancedSemaphore
import org.codehaus.groovy.runtime.TimeCategory
import groovy.time.Duration
import java.util.concurrent.CountDownLatch
import org.gparallelizer.actors.pooledActors.ActorReplyException
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default Actor implementation designed to be extended with actual message queue and the act() method.
 * The actor must be started after construction  by calling the start() method. This will start the background
 * actor thread, which first calls an afterStart() method of the Actor, if the method exists, and then keeps
 * calling the act() method, until the stop() method is called or the actor thread is interrupted directly.
 * Before the actor thread finishes an beforeStop() method is called, if exists.
 * After it stops the afterStop(List unprocessedMessages) is called, if exists,
 * with all the unprocessed messages from the queue as a parameter.
 * The Actor can be restarted be calling start() again.
 * Each Actor can define lifecycle observing methods, which will be called by the Actor's background thread whenever a certain lifecycle event occurs.
 * <ul>
 * <li>afterStart() - called immediatelly after the Actor's background thread has been started, before the act() method is called the first time.</li>
 * <li>beforeStop() - called right before the actor stops.</li>
 * <li>afterStop(List undeliveredMessages) - called right after the actor is stopped, passing in all the messages from the queue.</li>
 * <li>onInterrupt(InterruptedException? e) - called when the actor's thread gets interrupted. Thread interruption will result in the stopping the actor in any case.</li>
 * <li>onException(Throwable e) - called when an exception occurs in the actor's thread. Throwing an exception from this method will stop the actor.</li>
 * </ul>
 *
 * @author Vaclav Pech
 * Date: Jan 7, 2009
 */
abstract public class AbstractActor implements ThreadedActor {

    /**
     * Queue for the messages
     */
    final BlockingQueue<ActorMessage> messageQueue;
    //todo should be private but the closure in doReceive() method would not see it

    /**
     * The actors background thread.
     */
    protected volatile Thread actorThread;

    /**
     * Flag indicating Actor's liveness status.
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    //todo Is it required?
    /**
     * PRevents race condition on the started flag
     */
    private final EnhancedSemaphore startupLock = new EnhancedSemaphore(1);

    /**
     * The actor group to which the actor belongs
     */
    volatile ActorGroup actorGroup = Actors.defaultActorGroup

    /**
     * Indicates whether the actor's group can be changed. It is typically not changeable after actor starts.
     */
    private volatile boolean groupMembershipChangeable = true

    //todo should be private ut wouldm't work
    /**
     * A list of senders for the currently procesed messages
     */
    List senders = []

    /**
     * Indicates whether the actor should enhance messages to enable sending replies to their senders
     */
    private volatile boolean sendRepliesFlag = true

    /**
     * Creates a new Actor using the passed-in queue to store incoming messages.
     */
    public AbstractActor(final BlockingQueue<ActorMessage> messageQueue) {
        if (messageQueue == null) throw new IllegalArgumentException("Actor message queue must not be null.")
        this.messageQueue = messageQueue;
    }

    /**
     * Sets the actor's group.
     * It can only be invoked before the actor is started.
     */
    public final void setActorGroup(ActorGroup group) {
        if (!groupMembershipChangeable) throw new IllegalStateException("Cannot set actor's group on a started actor.")
        if (!group) throw new IllegalArgumentException("Cannot set actor's group to null.")
        actorGroup = group
    }

    /**
     * Enabled the actor and received messages to have the reply()/replyIfExists() methods called on them.
     * Sending replies is enabled by default.
     */
    final void enableSendingReplies() {
        sendRepliesFlag = true
    }

    /**
     * Disables the actor and received messages to have the reply()/replyIfExists() methods called on them.
     * Calling reply()/replyIfExist() on the actor will result in IllegalStateException being thrown.
     * Calling reply()/replyIfExist() on a received message will result in MissingMethodException being thrown.
     * Sending replies is enabled by default.
     */
    final void disableSendingReplies() {
        sendRepliesFlag = false
    }

    /**
     * Starts the Actor. No messages can be send or received before an Actor is started.
     */
    public final Actor start() {
        groupMembershipChangeable = false
        //todo should be inlined but currently it wouldn't be visible inside the closure if mixin is used
        def localStarted = started
        //todo should be inlined but currently it wouldn't be visible inside the closure if mixin is used
        def localStartupLock = startupLock

        localStartupLock.withSemaphore {
            if (localStarted.getAndSet(true)) throw new IllegalStateException("Actor already started")
        }

        actorThread = actorGroup.threadFactory.newThread({
            try {
                ReplyRegistry.registerCurrentActorWithThread this
                if (delegate.respondsTo('afterStart')) delegate.afterStart()
                while (!Thread.currentThread().interrupted()) {
                    try {
                        use(TimeCategory) {
                            act();
                        }
                    } catch (InterruptedException e) {
                        if (delegate.respondsTo('onInterrupt')) delegate.onInterrupt(e)
                        Thread.currentThread().interrupt()
                    } catch (Throwable e) {
                        reportError(delegate, e)
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace(System.err) //invoked when the onException handler threw an exception
            } finally {
                senders.clear()
                try {
                    if (delegate.respondsTo('beforeStop')) delegate.beforeStop()
                    localStartupLock.withSemaphore {
                        localStarted.set(false)
                        if (this.respondsTo('afterStop')) this.afterStop(sweepQueue())
                    }
                } catch (Throwable e) {
                    try {
                        reportError(delegate, e)
                    } catch (Throwable ex) {ex.printStackTrace(System.err)} //invoked when the onException handler threw an exception
                }
                ReplyRegistry.deregisterCurrentActorWithThread()
            }
        } as Runnable)
        actorThread.name = createThreadName()
        actorThread.start()
        return this
    }

    /**
     * Stops the Actor. The background thread will be stopped, unprocessed messages will be passed to the afterStop
     * method, if exists.
     * Has no effect if the Actor is not started.
     */
    public final Actor stop() {
        actorThread?.interrupt()
        return this
    }

    /**
     * Checks the current status of the Actor.
     */
    public final boolean isActive() {
        return started.get()
    }

    /**
     * Checks whether the current thread is the actor's current thread.
     */
    public final boolean isActorThread() {
        return Thread.currentThread() == actorThread
    }

    //todo should be private but mixins woudn't work
    /**
     * Does the actual message receive using the supplied closure and wraps it with all necessary ceremony
     */
    final ActorMessage doReceive(Closure code) throws InterruptedException {
        checkState();
        return code()
    }

    //todo should be private, but woudn't be work
    /**
     * Adds reply() and replyIfExists() methods to the currentActor and the message.
     * These methods will call send() on the target actor (the sender of the original message).
     * The reply()/replyIfExists() methods invoked on the actor will be sent to all currently processed messages,
     * reply()/replyIfExists() invoked on a message will send a reply to the sender of that particular message only.
     * @param message The instance of ActorMessage wrapping the sender actor, who we need to be able to respond to,
     * plus the original message
     */
    final void enhanceReplies(List<ActorMessage> messages) {
        senders.clear()
        if (sendRepliesFlag) {
            for (message in messages) {
                senders << message?.sender
            }
            ReplyEnhancer.enhanceWithReplyMethodsToMessages(messages)
        }
    }

    /**
     * Retrieves a message from the message queue, waiting, if necessary, for a message to arrive.
     * @return The message retrieved from the queue.
     * @throws InterruptedException If the thread is interrupted during the wait. Should propagate up to stop the thread.
     */
    protected final Object receive() throws InterruptedException {
        Object message = doReceive {messageQueue.take()}
        enhanceReplies([message])
        return message?.payLoad
    }

    /**
     * Retrieves a message from the message queue, waiting, if necessary, for a message to arrive.
     * @param timeout how long to wait before giving up, in units of unit
     * @param timeUnit a TimeUnit determining how to interpret the timeout parameter
     * @return The message retrieved from the queue, or null, if the timeout expires.
     * @throws InterruptedException If the thread is interrupted during the wait. Should propagate up to stop the thread.
     */
    protected final Object receive(long timeout, TimeUnit timeUnit) throws InterruptedException {
        Object message = doReceive {messageQueue.poll(timeout, timeUnit)}
        enhanceReplies([message])
        return message?.payLoad
    }

    /**
     * Retrieves a message from the message queue, waiting, if necessary, for a message to arrive.
     * @param duration how long to wait before giving up, in units of unit
     * @return The message retrieved from the queue, or null, if the timeout expires.
     * @throws InterruptedException If the thread is interrupted during the wait. Should propagate up to stop the thread.
     */
    protected final Object receive(Duration duration) throws InterruptedException {
        return receive(duration.toMilliseconds(), TimeUnit.MILLISECONDS);
    }

    /**
     * Retrieves a message from the message queue, waiting, if necessary, for a message to arrive.
     * The message retrieved from the queue is passed into the handler as the only parameter.
     * @param handler A closure accepting the retrieved message as a parameter, which will be invoked after a message is received.
     * @throws InterruptedException If the thread is interrupted during the wait. Should propagate up to stop the thread.
     */
    protected final void receive(Closure handler) throws InterruptedException {
        int maxNumberOfParameters = handler.maximumNumberOfParameters
        if (maxNumberOfParameters == 0) {
            ActorMessage message = doReceive {messageQueue.take()}
            try {
                enhanceReplies([message])
                handler.call()
            } finally {
                senders.clear()
            }

        } else {
            final List<ActorMessage> messages = []
            for (i in 1..maxNumberOfParameters) {
                messages << doReceive {messageQueue.take()}
            }
            try {
                enhanceReplies(messages)
                handler.call(* messages*.payLoad)
            } finally {
                senders.clear()
            }

        }
    }

    /**
     * Retrieves a message from the message queue, waiting, if necessary, for a message to arrive.
     * The message retrieved from the queue is passed into the handler as the only parameter.
     * A null value is passed into the handler, if the timeout expires
     * @param timeout how long to wait before giving up, in units of unit
     * @param timeUnit a TimeUnit determining how to interpret the timeout parameter
     * @param handler A closure accepting the retrieved message as a parameter, which will be invoked after a message is received.
     * @throws InterruptedException If the thread is interrupted during the wait. Should propagate up to stop the thread.
     */
    protected final void receive(long timeout, TimeUnit timeUnit, Closure handler) throws InterruptedException {
        int maxNumberOfParameters = handler.maximumNumberOfParameters
        if (maxNumberOfParameters == 0) {
            ActorMessage message = doReceive {messageQueue.poll(timeout, timeUnit)}
            try {
                enhanceReplies([message])
                handler.call()
            } finally {
                senders.clear()
            }

        } else {
            long stopTime = timeUnit.toMillis(timeout) + System.currentTimeMillis()
            boolean nullAppeared = false  //Ignore further potential messages once a null is retrieved (due to a timeout)

            final List<ActorMessage> messages = []
            for (i in 1..maxNumberOfParameters) {
                if (nullAppeared) messages << null
                else {
                    ActorMessage message = doReceive {
                        messageQueue.poll(Math.max(stopTime - System.currentTimeMillis(), 0), TimeUnit.MILLISECONDS)
                    }
                    nullAppeared = (message == null)
                    messages << message
                }
            }
            try {
                enhanceReplies(messages)
                handler.call(* messages*.payLoad)
            } finally {
                senders.clear()
            }
        }
    }

    /**
     * Retrieves a message from the message queue, waiting, if necessary, for a message to arrive.
     * The message retrieved from the queue is passed into the handler as the only parameter.
     * A null value is passed into the handler, if the timeout expires
     * @param duration how long to wait before giving up, in units of unit
     * @param handler A closure accepting the retrieved message as a parameter, which will be invoked after a message is received.
     * @throws InterruptedException If the thread is interrupted during the wait. Should propagate up to stop the thread.
     */
    protected final void receive(Duration duration, Closure handler) throws InterruptedException {
        receive(duration.toMilliseconds(), TimeUnit.MILLISECONDS, handler)
    }

    /**
     * Sends a reply to all currently processed messages. Throws ActorReplyException if some messages
     * have not been sent by an actor. For such cases use replyIfExists().
     * Calling reply()/replyIfExist() on the actor with disabled replying (through the disableSendingReplies() method)
     * will result in IllegalStateException being thrown.
     * Sending replies is enabled by default.
     * @throws ActorReplyException If some of the replies failed to be sent.
     */
    protected final void reply(Object message) {
        assert senders != null
        if (!sendRepliesFlag) throw new IllegalStateException("Cannot send a reply $message. Replies have been disabled.")
        if (!senders.isEmpty()) {
            List<Exception> exceptions = []
            for (sender in senders) {
                if (sender != null) {
                    try { sender.send message } catch (IllegalStateException e) {exceptions << e }
                }
                else exceptions << new IllegalArgumentException("Cannot send a reply message ${message} to a null recipient.")
            }
            if (!exceptions.empty) throw new ActorReplyException('Failed sending some replies. See the issues field for details', exceptions)
        } else {
            throw new ActorReplyException("Cannot send replies. The list of recipients is empty.")
        }
    }

    /**
     * Sends a reply to all currently processed messages, which have been sent by an actor.
     * Ignores potential errors when sending the replies, like no sender or sender already stopped.
     * Calling reply()/replyIfExist() on the actor with disabled replying (through the disableSendingReplies() method)
     * will result in IllegalStateException being thrown.
     * Sending replies is enabled by default.
     */
    protected final void replyIfExists(Object message) {
        assert senders != null
        if (!sendRepliesFlag) throw new IllegalStateException("Cannot send a reply $message. Replies have been disabled.")
        for (sender in senders) {
            try {
                sender?.send message
            } catch (IllegalStateException ignore) { }
        }
    }

    /**
     * Adds the message to the Actor's message queue.
     * The method will wait for space to become available in the queue, if it is full.
     * It can only be called on a started Actor.
     * @return The same Actor instance
     * @throws InterruptedException If the thread is interrupted during the wait.
     */
    public final Actor send(Object message) throws InterruptedException {
        checkState()
        messageQueue.put(ActorMessage.build(message))
        return this
    }

    /**
     * Sends a message and waits for a reply.
     * Returns the reply or throws an IllegalStateException, if the target actor cannot reply.
     * @return The message that came in reply to the original send.
     */
    public final sendAndWait(Object message) {
        new SendAndWaitActor(this, message).start().result
    }

    /**
     * Sends a message and waits for a reply. Timeouts after the specified timeout. In case of timeout returns null.
     * Returns the reply or throws an IllegalStateException, if the target actor cannot reply.
     * @return The message that came in reply to the original send.
     */
    public final sendAndWait(long timeout, TimeUnit timeUnit, Object message) {
        new SendAndWaitActor(this, message, timeUnit.toMillis(timeout)).start().result
    }

    /**
     * Sends a message and waits for a reply. Timeouts after the specified timeout. In case of timeout returns null.
     * Returns the reply or throws an IllegalStateException, if the target actor cannot reply.
     * @return The message that came in reply to the original send.
     */
    public final sendAndWait(Duration duration, Object message) {
        return sendAndWait(duration.toMilliseconds(), TimeUnit.MILLISECONDS, message)
    }

    /**
     * Adds the message to the Actor's message queue.
     * The method will wait for space to become available in the queue, if it is full.
     * It can only be called on a started Actor.
     * @return The same Actor instance
     * @throws InterruptedException If the thread is interrupted during the wait.
     */
    public final Actor leftShift(Object message) throws InterruptedException { send message }

    /**
     * This method is called periodically from the Actor's thread until the Actor is stopped
     * with a call to the stop() method or the background thread is interrupted.
     * Provides an extension point for subclasses to provide their custom Actor's message handling code.
     * The default implementation throws UnsupportedOperationException.
     */
    protected void act() {
        throw new UnsupportedOperationException("The act() method must be overriden")
    }

    /**
     * Returns the actor's thread
     */
    protected final Thread getActorThread() { actorThread }

    /**
     * Joins the actor's thread
     * @param milis Timeout in miliseconds
     */
    public final void join(long milis) {
        actorThread?.join(milis)
    }

    //todo should be private, but closures demand higher visibility
    void reportError(def delegate, Throwable e) {
        if (delegate.respondsTo('onException')) delegate.onException(e)
        else {
            System.err.println("An exception occured in the Actor thread ${Thread.currentThread().name}")
            e.printStackTrace(System.err)
        }
    }

    //todo should be private, but closures demand higher visibility
    /**
     * Clears the message queue returning all the messages it held.
     * @return The messages stored in the queue
     */
    final List sweepQueue() {
        def messages = []
        ActorMessage message = messageQueue.poll()
        while (message != null) {
            if (message.payLoad.respondsTo('onDeliveryError')) message.payLoad.onDeliveryError()
            messages << message
            message = messageQueue.poll()
        }
        return messages
    }

    //todo should be private, but mixins would not work properly
    /**
     * Checks, whether the Actor is active.
     * @throws IllegalStateException If the Actor is not active.
     */
    void checkState() {
        if (!started.get()) throw new IllegalStateException("The actor hasn't been started.");
    }

    //todo should be private but closures demand higher visibility
    /**
     * Created a JVM-unique name for Actors' threads.
     */
    final String createThreadName() {
        "Actor Thread ${threadCount.incrementAndGet()}"
    }

    /**
     * Unique counter for Actors' threads
     */
    private static final AtomicLong threadCount = new AtomicLong(0)
}

/**
 * Sends a message to the specified actor and waits for reply.
 * The message is enhanced to send notification in case the target actor terminates without processing the message.
 * Exceptions are re-throvn from the getResult() method.
 */
final class SendAndWaitActor extends DefaultActor {
    private Actor targetActor
    private Object message
    //todo use Phaser instead once available to keep the thread running
    private CountDownLatch latch = new CountDownLatch(1)
    private Object result
    private long timeout = -1

    def SendAndWaitActor(final targetActor, final message) {
        this.targetActor = targetActor;
        this.message = message
    }

    def SendAndWaitActor(final targetActor, final message, final long timeout) {
        this(targetActor, message)
        this.timeout = timeout
    }

    void act() {
        message.getMetaClass().onDeliveryError = {->
            this << new IllegalStateException('Cannot deliver the message. The target actor may not be active.')
        }

        try {
            targetActor << message
            result = (timeout < 0) ? receive() : receive(timeout, TimeUnit.MILLISECONDS)
        } catch (Exception e) {
            result = e
        } finally {
            latch.countDown()
            stop()
        }
    }

    Object getResult() {
        latch.await()
        if (result instanceof Exception) throw result else return result
    }
}