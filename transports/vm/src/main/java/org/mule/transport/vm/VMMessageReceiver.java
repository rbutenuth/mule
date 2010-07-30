/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.vm;

import org.mule.DefaultMuleMessage;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.endpoint.InboundEndpoint;
import org.mule.api.lifecycle.CreateException;
import org.mule.api.transport.Connector;
import org.mule.api.transport.PropertyScope;
import org.mule.transport.PollingReceiverWorker;
import org.mule.transport.TransactedPollingMessageReceiver;
import org.mule.util.queue.Queue;
import org.mule.util.queue.QueueSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import edu.emory.mathcs.backport.java.util.concurrent.RejectedExecutionException;

/**
 * <code>VMMessageReceiver</code> is a listener for events from a Mule service which then simply passes
 * the events on to the target service.
 */
public class VMMessageReceiver extends TransactedPollingMessageReceiver
{

    private VMConnector connector;
    private final Object lock = new Object();

    public VMMessageReceiver(Connector connector, FlowConstruct flowConstruct, InboundEndpoint endpoint)
        throws CreateException
    {
        super(connector, flowConstruct, endpoint);
        this.setReceiveMessagesInTransaction(endpoint.getTransactionConfig().isTransacted());
        this.connector = (VMConnector) connector;
    }

    /*
     * We only need to start scheduling this receiver if event queueing is enabled on the connector; otherwise
     * events are delivered via onEvent/onCall.
     */
    @Override
    protected void schedule() throws RejectedExecutionException, NullPointerException, IllegalArgumentException
    {
        if (!endpoint.getExchangePattern().hasResponse())
        {
            super.schedule();
        }
    }

    @Override
    protected void doDispose()
    {
        // template method
    }

    @Override
    protected void doConnect() throws Exception
    {
        if (!endpoint.getExchangePattern().hasResponse())
        {
            // Ensure we can create a vm queue
            QueueSession queueSession = connector.getQueueSession();
            Queue q = queueSession.getQueue(endpoint.getEndpointURI().getAddress());
            if (logger.isDebugEnabled())
            {
                logger.debug("Current queue depth for queue: " + endpoint.getEndpointURI().getAddress() + " is: "
                             + q.size());
            }
        }
    }

    @Override
    protected void doDisconnect() throws Exception
    {
        // template method
    }

    public void onMessage(MuleMessage message) throws MuleException
    {
        // Rewrite the message to treat it as a new message
        MuleMessage newMessage = new DefaultMuleMessage(message.getPayload(), message, connector.getMuleContext());

        /*
         * TODO review: onEvent can only be called by the VMMessageDispatcher - why is
         * this lock here and do we still need it? what can break if this receiver is run
         * concurrently by multiple dispatchers, which are isolated?
         */
        synchronized (lock)
        {
            routeMessage(newMessage);
        }
    }

    public MuleMessage onCall(MuleMessage message, boolean synchronous) throws MuleException
    {
        // Rewrite the message to treat it as a new message
        MuleMessage newMessage = createMessageCopy(message);
        return routeMessage(newMessage);
    }
    
    protected MuleMessage createMessageCopy(MuleMessage message)
    {
        MuleMessage newMessage = new DefaultMuleMessage(message.getPayload(), message, connector.getMuleContext());
        movePropertiesToInbound(newMessage);
        
        newMessage.setCorrelationId(message.getCorrelationId());
        newMessage.setCorrelationGroupSize(message.getCorrelationGroupSize());
        newMessage.setCorrelationSequence(message.getCorrelationSequence());
        
        return newMessage;
    }

    protected void movePropertiesToInbound(MuleMessage newMessage)
    {
        // TODO hackish way, needs to be reworked into an api - move outbound props (from dispatcher) to inbound (for receiver)
        Set<String> props = new HashSet<String>(newMessage.getInboundPropertyNames());
        for (String name : props)
        {
            newMessage.removeProperty(name, PropertyScope.INBOUND);
        }
        
        props = new HashSet<String>(newMessage.getInvocationPropertyNames());
        for (String name : props)
        {
            newMessage.removeProperty(name, PropertyScope.INVOCATION);
        }
        
        // clone to avoid CMEs
        props = new HashSet<String>(newMessage.getOutboundPropertyNames());
        for (String name : props)
        {
            final Object value = newMessage.removeProperty(name, PropertyScope.OUTBOUND);
            newMessage.setInboundProperty(name, value);
        }
    }

    /**
     * It's impossible to process all messages in the receive transaction
     */
    @Override
    protected List<MuleMessage> getMessages() throws Exception
    {
        if (isReceiveMessagesInTransaction())
        {
            MuleEvent message = getFirstMessage();
            if (message == null)
            {
                return null;
            }
            
            List<MuleMessage> messages = new ArrayList<MuleMessage>(1);
            messages.add(message.getMessage());
            return messages;
        }
        else
        {
            return getFirstMessages();
        }
    }
    
    protected List<MuleMessage> getFirstMessages() throws Exception
    {
        // The queue from which to pull events
        QueueSession qs = connector.getQueueSession();
        Queue queue = qs.getQueue(endpoint.getEndpointURI().getAddress());

        // The list of retrieved messages that will be returned
        List<MuleMessage> messages = new LinkedList<MuleMessage>();

        /*
         * Determine how many messages to batch in this poll: we need to drain the queue quickly, but not by
         * slamming the workManager too hard. It is impossible to determine this more precisely without proper
         * load statistics/feedback or some kind of "event cost estimate". Therefore we just try to use half
         * of the receiver's workManager, since it is shared with receivers for other endpoints.
         */
        int maxThreads = connector.getReceiverThreadingProfile().getMaxThreadsActive();
        // also make sure batchSize is always at least 1
        int batchSize = Math.max(1, Math.min(queue.size(), ((maxThreads / 2) - 1)));

        // try to get the first event off the queue
        MuleEvent message = (MuleEvent) queue.poll(connector.getQueueTimeout());

        if (message != null)
        {
            // keep first dequeued event
            messages.add(message.getMessage());

            // keep batching if more events are available
            for (int i = 0; i < batchSize && message != null; i++)
            {
                message = (MuleEvent)queue.poll(0);
                if (message != null)
                {
                    messages.add(message.getMessage());
                }
            }
        }

        // let our workManager handle the batch of events
        return messages;
    }
    
    protected MuleEvent getFirstMessage() throws Exception
    {
        // The queue from which to pull events
        QueueSession qs = connector.getQueueSession();
        Queue queue = qs.getQueue(endpoint.getEndpointURI().getAddress());
        // try to get the first event off the queue
        return (MuleEvent) queue.poll(connector.getQueueTimeout());
    }

    @Override
    protected void processMessage(Object msg) throws Exception
    {
        MuleMessage message = (MuleMessage) msg;

        // Rewrite the message to treat it as a new message
        MuleMessage newMessage = createMessageCopy(message);
        routeMessage(newMessage);
    }

    /*
     * We create our own "polling" worker here since we need to evade the standard scheduler.
     */
    @Override
    protected PollingReceiverWorker createWork()
    {
        return new VMReceiverWorker(this);
    }
    
    /*
     * Even though the VM transport is "polling" for messages, the nonexistent cost of accessing the queue is
     * a good reason to not use the regular scheduling mechanism in order to both minimize latency and
     * maximize throughput.
     */
    protected static class VMReceiverWorker extends PollingReceiverWorker
    {

        public VMReceiverWorker(VMMessageReceiver pollingMessageReceiver)
        {
            super(pollingMessageReceiver);
        }

        @Override
        public void run()
        {
            /*
             * We simply run our own polling loop all the time as long as the receiver is started. The
             * blocking wait defined by VMConnector.getQueueTimeout() will prevent this worker's receiver
             * thread from busy-waiting.
             */
            while (this.getReceiver().isConnected())
            {
                super.run();
            }
        }
    }

}
