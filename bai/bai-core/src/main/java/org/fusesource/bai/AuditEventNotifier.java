/*
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.bai;

import org.apache.camel.*;
import org.apache.camel.management.PublishEventNotifier;
import org.apache.camel.management.event.*;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.ServiceHelper;
import org.apache.camel.util.URISupport;

import java.util.Arrays;
import java.util.EventObject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A notifier of {@link AuditEvent} objects
 *
 * { _id: <breadcrumbId>,
     exchanges: [
     { timestamp: <timestamp>,
        endpointUri: <uri>,
        in: <inMessage>,
        out: <outMessage>
     },
     { timestamp: <timestamp>,
        endpointUri: <uri>,
        in: <inMessage>,
        out: <outMessage>
     },
     { timestamp: <timestamp>,
        endpointUri: <uri>,
        in: <inMessage>,
        out: <outMessage>
     }
   ],
   
   failures: [
     { timestamp: <timestamp>,
       error: <exception and message>
     }
   ],
   
   redeliveries: 
     { endpoint: [timestamps],
       endpoint: [timestamps]
     }
   ],
   
   
}
 * @author raul
 *
 */
public class AuditEventNotifier extends PublishEventNotifier {

    // by default accept all
	private List<String> createdRegex = Arrays.asList(".*");
	private List<String> completedRegex = Arrays.asList(".*");
	private List<String> sendingRegex = Arrays.asList(".*");
	private List<String> sentRegex = Arrays.asList(".*");
	private List<String> failureRegex = Arrays.asList(".*");
	private List<String> redeliveryRegex = Arrays.asList(".*");

    private Predicate createdFilter;
    private Predicate completedFilter;
    private Predicate sendingFilter;
    private Predicate sentFilter;
    private Predicate failureFilter;
    private Predicate redeliveryFilter;

    private boolean includeCreatedEvents = true;
    private boolean includeCompletedEvents = true;
    private boolean includeSendingEvents = true;
    private boolean includeSentEvents = true;
    private boolean includeFailureEvents = true;
    private boolean includeRedeliveryEvents = true;


    private CamelContext camelContext;
    private Endpoint endpoint;
    private String endpointUri;
    private Producer producer;

    public AuditEventNotifier() {
		setIgnoreCamelContextEvents(true);
		setIgnoreRouteEvents(true);
		setIgnoreServiceEvents(true);
	}
	
	@Override
	public boolean isEnabled(EventObject event) {
        EventObject coreEvent = event;
        AbstractExchangeEvent exchangeEvent = null;
        if (event instanceof AuditEvent) {
            AuditEvent auditEvent = (AuditEvent) event;
            coreEvent = auditEvent.getEvent();
        }
        if (event instanceof AbstractExchangeEvent) {
            exchangeEvent = (AbstractExchangeEvent) event;
        }
        Predicate filter = null;
        List<String> compareWith = null;
        if (coreEvent instanceof ExchangeCreatedEvent) {
            if (!includeCreatedEvents) return false;
            compareWith = createdRegex;
            filter = getCreatedFilter();
        } else if (coreEvent instanceof ExchangeCompletedEvent) {
            if (!includeCompletedEvents) return false;
            compareWith = completedRegex;
            filter = getCompletedFilter();
        } else if (coreEvent instanceof ExchangeSendingEvent) {
            if (!includeSendingEvents) return false;
            compareWith = sendingRegex;
            filter = getSendingFilter();
        } else if (coreEvent instanceof ExchangeSentEvent) {
            if (!includeSentEvents) return false;
            compareWith = sentRegex;
            filter = getSentFilter();
        } else if (coreEvent instanceof ExchangeRedeliveryEvent) {
            if (!includeRedeliveryEvents) return false;
            compareWith = redeliveryRegex;
            filter = getRedeliveryFilter();
        }
        // logic if it's a failure is different; we compare against Exception
        else if (coreEvent instanceof ExchangeFailedEvent) {
            if (!includeFailureEvents) return false;
            ExchangeFailedEvent failedEvent = (ExchangeFailedEvent) coreEvent;
            String exceptionClassName = failedEvent.getExchange().getException().getClass().getCanonicalName();
            filter = getFailureFilter();
            return testRegexps(exceptionClassName, failureRegex, filter, exchangeEvent);
        }
        // TODO: Failure handled
        String uri = endpointUri(event);
        return uri == null || compareWith == null ? false : testRegexps(uri, compareWith, filter, exchangeEvent);

    }

    public static String endpointUri(EventObject event) {
        if (event instanceof AuditEvent) {
            AuditEvent auditEvent = (AuditEvent) event;
            return auditEvent.getEndpointURI();
        } else if (event instanceof ExchangeSendingEvent) {
            ExchangeSendingEvent sentEvent = (ExchangeSendingEvent) event;
            return sentEvent.getEndpoint().getEndpointUri();
        } else if (event instanceof ExchangeSentEvent) {
            ExchangeSentEvent sentEvent = (ExchangeSentEvent) event;
            return sentEvent.getEndpoint().getEndpointUri();
        } else if (event instanceof AbstractExchangeEvent) {
            AbstractExchangeEvent ae = (AbstractExchangeEvent) event;
            Exchange exchange = ae.getExchange();
            if (event instanceof ExchangeFailureHandledEvent || event instanceof ExchangeFailedEvent) {
                return exchange.getProperty(Exchange.FAILURE_ENDPOINT, String.class);
            } else {
                Endpoint fromEndpoint = exchange.getFromEndpoint();
                if (fromEndpoint != null) {
                    return fromEndpoint.getEndpointUri();
                }
            }
        }
        return null;
    }

    private boolean testRegexps(String endpointURI, List<String> regexps, Predicate filter, AbstractExchangeEvent exchangeEvent) {
        // if the endpoint URI is null, we have an event that is not related to an endpoint, e.g. a failure in a processor; audit it
        if (endpointURI == null) {
            return testFilter(filter, exchangeEvent);
        }
		for (String regex : regexps) {
			if (endpointURI.matches(regex)) {
                return testFilter(filter, exchangeEvent);
			}
		}
		return false;
	}

    private boolean testFilter(Predicate filter, AbstractExchangeEvent exchangeEvent) {
        if (filter == null) {
            return true;
        } else {
            Exchange exchange = exchangeEvent.getExchange();
            if (exchange != null) {
                return filter.matches(exchange);
            }
        }
        return false;
    }

    /**
     * Add a unique dispatchId property to the original Exchange, which will come back to us later.
     * Camel does not correlate the individual sends/dispatches of the same exchange to the same endpoint, e.g.
     * Exchange X sent to http://localhost:8080, again sent to http://localhost:8080... When both happen in parallel, and are marked in_progress in BAI, when the Sent or Completed
     * events arrive, BAI won't know which record to update (ambiguity)
     * So to overcome this situation, we enrich the Exchange with a DispatchID only when Created or Sending
     */
	@Override
    public void notify(EventObject event) throws Exception {
        AuditEvent auditEvent = null;
        AbstractExchangeEvent ae = null;
        if (event instanceof AuditEvent) {
            auditEvent = (AuditEvent) event;
            ae = auditEvent.getEvent();
        } else if (event instanceof AbstractExchangeEvent) {
            ae = (AbstractExchangeEvent) event;
            auditEvent = createAuditEvent(ae);
        }

        if (ae == null || auditEvent == null) {
            log.debug("Ignoring events like " + event + " as its neither a AbstractExchangeEvent or AuditEvent");
            return;
        }
	    if (event instanceof ExchangeSendingEvent || event instanceof ExchangeCreatedEvent) {
	        ae.getExchange().setProperty(AuditConstants.DISPATCH_ID, ae.getExchange().getContext().getUuidGenerator().generateUuid());
	    }
	    
	    // only notify when we are started
        if (!isStarted()) {
            log.debug("Cannot publish event as notifier is not started: {}", event);
            return;
        }

        // only notify when camel context is running
        if (!camelContext.getStatus().isStarted()) {
            log.debug("Cannot publish event as CamelContext is not started: {}", event);
            return;
        }

        Exchange exchange = producer.createExchange();
        exchange.getIn().setBody(auditEvent);

        // make sure we don't send out events for this as well
        // mark exchange as being published to event, to prevent creating new events
        // for this as well (causing a endless flood of events)
        exchange.setProperty(Exchange.NOTIFY_EVENT, Boolean.TRUE);
        try {
            producer.process(exchange);
        } finally {
            // and remove it when its done
            exchange.removeProperty(Exchange.NOTIFY_EVENT);
        }
    }

    /**
     * Factory method to create a new {@link AuditEvent} in case a sub class wants to create a different derived kind of event
     */
    protected AuditEvent createAuditEvent(AbstractExchangeEvent ae) {
        return new AuditEvent(ae.getExchange(), ae);
    }

    /**
	 * Substitute all arrays with CopyOnWriteArrayLists
	 */
	@Override
	protected void doStart() throws Exception {
		sendingRegex = new CopyOnWriteArrayList<String>(sendingRegex);
		sentRegex = new CopyOnWriteArrayList<String>(sentRegex);
		failureRegex = new CopyOnWriteArrayList<String>(failureRegex);
		redeliveryRegex = new CopyOnWriteArrayList<String>(redeliveryRegex);
	    
		ObjectHelper.notNull(camelContext, "camelContext", this);
        if (endpoint == null && endpointUri == null) {
            throw new IllegalArgumentException("Either endpoint or endpointUri must be configured");
        }

        if (endpoint == null) {
            endpoint = camelContext.getEndpoint(endpointUri);
        }

        producer = endpoint.createProducer();
        ServiceHelper.startService(producer);

	}

	public List<String> getSendingRegex() {
		return sendingRegex;
	}

	public void setSendingRegex(List<String> sendingRegex) {
		this.sendingRegex = sendingRegex;
	}

	public List<String> getSentRegex() {
		return sentRegex;
	}

	public void setSentRegex(List<String> sentRegex) {
		this.sentRegex = sentRegex;
	}

	public List<String> getFailureRegex() {
		return failureRegex;
	}

	public void setFailureRegex(List<String> failureRegex) {
		this.failureRegex = failureRegex;
	}

	public List<String> getRedeliveryRegex() {
		return redeliveryRegex;
	}

	public void setRedeliveryRegex(List<String> redeliveryRegex) {
		this.redeliveryRegex = redeliveryRegex;
	}
	
	public CamelContext getCamelContext() {
        return camelContext;
    }

    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public String getEndpointUri() {
        return endpointUri;
    }

    public void setEndpointUri(String endpointUri) {
        this.endpointUri = endpointUri;
    }

    public Predicate getFailureFilter() {
        return failureFilter;
    }

    public void setFailureFilter(Predicate failureFilter) {
        this.failureFilter = failureFilter;
    }

    public Predicate getSendingFilter() {
        return sendingFilter;
    }

    public void setSendingFilter(Predicate sendingFilter) {
        this.sendingFilter = sendingFilter;
    }

    public Predicate getSentFilter() {
        return sentFilter;
    }

    public void setSentFilter(Predicate sentFilter) {
        this.sentFilter = sentFilter;
    }

    public Predicate getRedeliveryFilter() {
        return redeliveryFilter;
    }

    public void setRedeliveryFilter(Predicate redeliveryFilter) {
        this.redeliveryFilter = redeliveryFilter;
    }

    public Predicate getCompletedFilter() {
        return completedFilter;
    }

    public void setCompletedFilter(Predicate completedFilter) {
        this.completedFilter = completedFilter;
    }

    public Predicate getCreatedFilter() {
        return createdFilter;
    }

    public void setCreatedFilter(Predicate createdFilter) {
        this.createdFilter = createdFilter;
    }

    public boolean isIncludeCompletedEvents() {
        return includeCompletedEvents;
    }

    public void setIncludeCompletedEvents(boolean includeCompletedEvents) {
        this.includeCompletedEvents = includeCompletedEvents;
    }

    public boolean isIncludeCreatedEvents() {
        return includeCreatedEvents;
    }

    public void setIncludeCreatedEvents(boolean includeCreatedEvents) {
        this.includeCreatedEvents = includeCreatedEvents;
    }

    public boolean isIncludeFailureEvents() {
        return includeFailureEvents;
    }

    public void setIncludeFailureEvents(boolean includeFailureEvents) {
        this.includeFailureEvents = includeFailureEvents;
    }

    public boolean isIncludeRedeliveryEvents() {
        return includeRedeliveryEvents;
    }

    public void setIncludeRedeliveryEvents(boolean includeRedeliveryEvents) {
        this.includeRedeliveryEvents = includeRedeliveryEvents;
    }

    public boolean isIncludeSendingEvents() {
        return includeSendingEvents;
    }

    public void setIncludeSendingEvents(boolean includeSendingEvents) {
        this.includeSendingEvents = includeSendingEvents;
    }

    public boolean isIncludeSentEvents() {
        return includeSentEvents;
    }

    public void setIncludeSentEvents(boolean includeSentEvents) {
        this.includeSentEvents = includeSentEvents;
    }

    public List<String> getCompletedRegex() {
        return completedRegex;
    }

    public void setCompletedRegex(List<String> completedRegex) {
        this.completedRegex = completedRegex;
    }

    public List<String> getCreatedRegex() {
        return createdRegex;
    }

    public void setCreatedRegex(List<String> createdRegex) {
        this.createdRegex = createdRegex;
    }

    @Override
    protected void doStop() throws Exception {
        ServiceHelper.stopService(producer);
    }

    @Override
    public String toString() {
        return "PublishEventNotifier[" + (endpoint != null ? endpoint : URISupport.sanitizeUri(endpointUri)) + "]";
    }

}