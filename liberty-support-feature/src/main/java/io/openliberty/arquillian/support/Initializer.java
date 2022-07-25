/*
 * Copyright 2018, 2022 IBM Corporation, Red Hat Middleware LLC, and individual contributors
 * identified by the Git commit log. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openliberty.arquillian.support;

import java.lang.management.ManagementFactory;
import java.util.Dictionary;
import java.util.Hashtable;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;

import com.ibm.ws.container.service.state.ApplicationStateListener;
import com.ibm.ws.ffdc.FFDC;

public class Initializer implements BundleActivator {

    private static final ObjectName on;

    static {
        StringBuilder sb = new StringBuilder("LibertyArquillian:");
        sb.append("type=").append("DeploymentExceptionMBean");
        try {
            on = new ObjectName(sb.toString());
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }
    }

	private ServiceRegistration<ApplicationStateListener> appStateListenerRegistration;

    private IncidentListener listener = new IncidentListener();

    @Override
    public void start(BundleContext bContext) {
        // Register the listener as an incident forwarder
        FFDC.registerIncidentForwarder(listener);
        
        // Register the listener as an ApplicationStateListener
        Dictionary<String, Object> properties = new Hashtable<>();
        // Big number for the ranking so we get called early.
        // Other components use this event to initialize things for the app and may throw validation exceptions,
        // we need to know the app that's starting before an exception is thrown
        properties.put(Constants.SERVICE_RANKING, 5000); 
        appStateListenerRegistration = bContext.registerService(ApplicationStateListener.class, listener, properties);
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            mbs.registerMBean(new DeploymentExceptionMBeanImpl(listener), on);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void stop(BundleContext ctx) {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            mbs.unregisterMBean(on);
        } catch (Exception e) {
            e.printStackTrace();
        }
        FFDC.deregisterIncidentForwarder(listener);
        appStateListenerRegistration.unregister();
    }

}
