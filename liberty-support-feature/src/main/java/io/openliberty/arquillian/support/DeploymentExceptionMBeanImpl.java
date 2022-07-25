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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import javax.management.MBeanException;
import javax.management.NotCompliantMBeanException;
import javax.management.StandardMBean;

import io.openliberty.arquillian.support.IncidentListener.ExceptionInfo;

public class DeploymentExceptionMBeanImpl extends StandardMBean implements DeploymentExceptionMBean {

    private static final Set<Class<?>> topClasses = new HashSet<>();
    static {
        topClasses.add(Object.class);
        topClasses.add(Throwable.class);
        topClasses.add(Exception.class);
        topClasses.add(Error.class);
        topClasses.add(RuntimeException.class);
    }

    private final IncidentListener listener;

    DeploymentExceptionMBeanImpl(IncidentListener listener) throws NotCompliantMBeanException {
        super(DeploymentExceptionMBean.class);
        this.listener = listener;
    }

    @Override
    public Object[] getDeploymentException(String appName, String format) throws MBeanException {

        ExceptionInfo ex = listener.getLastException();
        
        if (appName == null) {
            return new Object[] { Integer.valueOf(400), "No appName given" };
        }

        if (ex == null) {
            return new Object[] { Integer.valueOf(400), "No exception logged" };
        }

        if (!appName.equals(ex.getAppName())) {
            return new Object[] { Integer.valueOf(400), "Last exception was not thrown by the requested app" };
        }

        if (format == null) {
            return new Object[] { Integer.valueOf(400), "Format parameter not set" };
        } else if (format.equals("text")) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            printText(pw, ex.getException());
            pw.flush();
            return new Object[] { Integer.valueOf(200), sw.toString() };
        } else if (format.equals("stack")) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.getException().printStackTrace(pw);
            pw.flush();
            return new Object[] { Integer.valueOf(200), sw.toString() };
        } else if (format.equals("serialize")) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(ex.getException());
                oos.flush();
                return new Object[] { Integer.valueOf(200), baos.toByteArray() };
            } catch (IOException io) {
                throw new MBeanException(io);
            }
        } else {
            return new Object[] { Integer.valueOf(400), "Invalid format requested" };
        }
    }

    @Override
    public void clear() {
        listener.clear();
    }

    private void printText(PrintWriter resp, Throwable exception) {
        Throwable t = exception;
        HashSet<Throwable> processed = new HashSet<>();
        while (t != null && !processed.contains(t)) {
            printClass(resp, t);
            processed.add(t);
            t = t.getCause();
        }
    }
    
    private void printClass(PrintWriter resp, Throwable exception) {
        resp.print("exClass ");
        resp.print(exception.getClass().getName());
        resp.println();
        
        Class<?> clazz = exception.getClass().getSuperclass();
        while (clazz != null && !topClasses.contains(clazz)) {
            resp.print("exSuperclass ");
            resp.print(clazz.getName());
            resp.println();
            clazz = clazz.getSuperclass();
        }
        
        resp.println(exception.getMessage());
    }
}
