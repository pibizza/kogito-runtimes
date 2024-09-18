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
package org.jbpm.process.instance;

import java.util.Optional;

import org.jbpm.process.instance.event.KogitoProcessEventSupportImpl;
import org.jbpm.process.instance.impl.DefaultProcessInstanceManager;
import org.kie.api.event.process.ProcessEventListener;
import org.kie.kogito.auth.IdentityProvider;
import org.kie.kogito.internal.process.event.KogitoProcessEventListener;
import org.kie.kogito.internal.process.event.KogitoProcessEventSupport;
import org.kie.kogito.internal.process.workitem.KogitoWorkItemManager;
import org.kie.kogito.jobs.JobsService;
import org.kie.kogito.process.ProcessEventListenerConfig;
import org.kie.kogito.process.WorkItemHandlerConfig;
import org.kie.kogito.services.signal.LightSignalManager;
import org.kie.kogito.signal.SignalManager;
import org.kie.kogito.signal.SignalManagerHub;
import org.kie.kogito.uow.UnitOfWorkManager;
import org.kie.kogito.uow.events.UnitOfWorkProcessEventListener;

public class AbstractProcessRuntimeServiceProvider implements ProcessRuntimeServiceProvider {

    private final JobsService jobsService;
    private final ProcessInstanceManager processInstanceManager;
    private final SignalManager signalManager;
    private final KogitoWorkItemManager workItemManager;
    private final KogitoProcessEventSupport eventSupport;
    private final UnitOfWorkManager unitOfWorkManager;

    public AbstractProcessRuntimeServiceProvider(JobsService jobsService,
            WorkItemHandlerConfig workItemHandlerProvider,
            ProcessEventListenerConfig processEventListenerProvider,
            SignalManagerHub compositeSignalManager,
            UnitOfWorkManager unitOfWorkManager,
            IdentityProvider identityProvider) {
        this.unitOfWorkManager = unitOfWorkManager;
        processInstanceManager = new DefaultProcessInstanceManager();
        signalManager = new LightSignalManager(
                id -> Optional.ofNullable(
                        processInstanceManager.getProcessInstance(id)),
                compositeSignalManager);
        this.eventSupport = new KogitoProcessEventSupportImpl(identityProvider);
        this.jobsService = jobsService;
        this.workItemManager = new LightWorkItemManager(processInstanceManager, signalManager, eventSupport);

        for (String workItem : workItemHandlerProvider.names()) {
            workItemManager.registerWorkItemHandler(
                    workItem, workItemHandlerProvider.forName(workItem));
        }

        this.eventSupport.addEventListener(new UnitOfWorkProcessEventListener(unitOfWorkManager));
        for (ProcessEventListener listener : processEventListenerProvider.listeners()) {
            this.eventSupport.addEventListener((KogitoProcessEventListener) listener);
        }
    }

    @Override
    public JobsService getJobsService() {
        return jobsService;
    }

    @Override
    public ProcessInstanceManager getProcessInstanceManager() {
        return processInstanceManager;
    }

    @Override
    public SignalManager getSignalManager() {
        return signalManager;
    }

    @Override
    public KogitoWorkItemManager getKogitoWorkItemManager() {
        return workItemManager;
    }

    @Override
    public KogitoProcessEventSupport getEventSupport() {
        return eventSupport;
    }

    @Override
    public UnitOfWorkManager getUnitOfWorkManager() {
        return unitOfWorkManager;
    }
}
