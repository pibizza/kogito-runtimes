/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jbpm.workflow.core.node;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jbpm.process.core.event.EventFilter;
import org.jbpm.process.core.event.EventTypeFilter;
import org.jbpm.workflow.core.Node;
import org.jbpm.workflow.core.impl.ExtendedNodeImpl;
import org.kie.api.definition.process.Connection;

import static org.jbpm.workflow.instance.WorkflowProcessParameters.WORKFLOW_PARAM_MULTIPLE_CONNECTIONS;

public class EventNode extends ExtendedNodeImpl implements EventNodeInterface {

    private static final long serialVersionUID = 510l;

    private List<EventFilter> filters = new ArrayList<>();
    private String inputVariableName;
    private String variableName;
    private String scope;

    public String getVariableName() {
        return variableName;
    }

    public void setVariableName(String variableName) {
        this.variableName = variableName;
    }

    public String getInputVariableName() {
        return inputVariableName;
    }

    public void setInputVariableName(String inputVariableName) {
        this.inputVariableName = inputVariableName;
    }

    public void addEventFilter(EventFilter eventFilter) {
        filters.add(eventFilter);
    }

    public void removeEventFilter(EventFilter eventFilter) {
        filters.remove(eventFilter);
    }

    public List<EventFilter> getEventFilters() {
        return filters;
    }

    public void setEventFilters(List<EventFilter> filters) {
        this.filters = filters;
    }

    public String getType() {
        for (EventFilter filter : filters) {
            if (filter instanceof EventTypeFilter) {
                return ((EventTypeFilter) filter).getType();
            }
        }
        return null;
    }

    @Override
    public boolean acceptsEvent(String type, Object event, Function<String, Object> resolver) {
        for (EventFilter filter : filters) {
            if (!filter.acceptsEvent(type, event, resolver)) {
                return false;
            }
        }
        return true;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public void validateAddIncomingConnection(final String type, final Connection connection) {
        super.validateAddIncomingConnection(type, connection);
        if (!Node.CONNECTION_DEFAULT_TYPE.equals(type)) {
            throw new IllegalArgumentException(
                    "This type of node [" + connection.getTo().getUniqueId() + ", " + connection.getTo().getName()
                            + "] only accepts default incoming connection type!");
        }
        if (getFrom() != null && !WORKFLOW_PARAM_MULTIPLE_CONNECTIONS.get(getProcess())) {
            throw new IllegalArgumentException(
                    "This type of node [" + connection.getTo().getUniqueId() + ", " + connection.getTo().getName()
                            + "] cannot have more than one incoming connection!");
        }
    }

    @Override
    public void validateAddOutgoingConnection(final String type, final Connection connection) {
        super.validateAddOutgoingConnection(type, connection);
        if (!Node.CONNECTION_DEFAULT_TYPE.equals(type)) {
            throw new IllegalArgumentException(
                    "This type of node [" + connection.getFrom().getUniqueId() + ", " + connection.getFrom().getName()
                            + "] only accepts default outgoing connection type!");
        }
        if (getTo() != null && !WORKFLOW_PARAM_MULTIPLE_CONNECTIONS.get(getProcess())) {
            throw new IllegalArgumentException(
                    "This type of node [" + connection.getFrom().getUniqueId() + ", " + connection.getFrom().getName()
                            + "] cannot have more than one outgoing connection!");
        }
    }

}
