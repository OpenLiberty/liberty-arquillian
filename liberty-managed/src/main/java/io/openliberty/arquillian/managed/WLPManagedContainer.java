/*
 * Copyright 2012, 2018, IBM Corporation, Red Hat Middleware LLC, and individual contributors
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
package io.openliberty.arquillian.managed;

import static java.util.logging.Level.FINER;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.enterprise.inject.spi.DefinitionException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.arquillian.container.test.api.Testable;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

public class WLPManagedContainer implements DeployableContainer<WLPManagedContainerConfiguration>
{

   // Environment variables that Liberty takes account of for messages.log location:
   // The precedence of the variables below can be thought of as building up from the bottom .
   // Strings that are "UPPER_CASE" are environment variables and Strings that are "camelCase"
   // can be set as properties. For properties, bootstrap.properties is read and used earlier
   // in the Liberty start process but once the server.xml <logging> element is read,
   // any equivalent value from there takes precedence.
   //
   private static final String DEFAULT_MESSAGES_LOG_NAME = "messages.log";
   private static final String MESSAGE_FILE_NAME = "messageFileName";
   private static final String MESSAGE_FILE_PROPERTY = "com.ibm.ws.logging.message.file.name";

   private static final String LOG_DIRECTORY = "logDirectory";
   private static final String LOG_DIRECTORY_PROPERTY = "com.ibm.ws.logging.log.directory";

   private static final String LOG_DIR = "LOG_DIR";
   private static final String WLP_OUTPUT_DIR = "WLP_OUTPUT_DIR";
   private static final String WLP_USER_DIR = "WLP_USER_DIR";
   private static final String JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS";
   
   private static final String ARQUILLIAN_SERVLET_NAME = "ArquillianServletRunner";


   private static final String className = WLPManagedContainer.class.getName();

   private static Logger log = Logger.getLogger(className);

   private static final String javaVmArgumentsDelimiter = " ";
   private static final String javaVmArgumentsIndicator = "-";

   private WLPManagedContainerConfiguration containerConfiguration;

   private JMXConnector jmxConnector;

   private MBeanServerConnection mbsc;

   private Process wlpProcess;

   private Thread shutdownThread;
   private Map<String, Long>archiveDeployTimes = new HashMap<>();

   @Override
   public void setup(WLPManagedContainerConfiguration configuration)
   {
      if (log.isLoggable(Level.FINER)) {
            log.entering(className, "setup");
      }

      this.containerConfiguration = configuration;

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "setup");
      }
   }

   // This method includes parts heavily based on the ManagedDeployableContainer.java in the jboss-as
   // managed container implementation as written by Thomas.Diesler@jboss.com
   @Override
   public void start() throws LifecycleException
   {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "start");
      }

      // Find WebSphere Liberty Profile VMs by looking for ws-launch.jar and the name of the server
      String vmid;
      VirtualMachine wlpvm = null;
      String serviceURL = null;

      try {
         vmid = findVirtualMachineIdByName(containerConfiguration.getServerName());
         // If it has already been started, throw exception unless we explicitly allow connecting to a running server
         if (vmid != null) {
            if (!containerConfiguration.isAllowConnectingToRunningServer())
               throw new LifecycleException("Connecting to an already running server is not allowed");

            wlpvm = VirtualMachine.attach(vmid);

            serviceURL = getVMLocalConnectorAddress(wlpvm);
            if (serviceURL == null)
               throw new LifecycleException("Unable to retrieve connector address for localConnector");
         } else {

            if (containerConfiguration.isAddLocalConnector()) {
                // Get path to server.xml
                String serverXML = getServerXML();
                if ("defaultServer".equals(containerConfiguration.getServerName()) && !new File(serverXML).exists()) {
                    // If server.xml doesn't exist for the default server, we may be dealing with a new
                    // installation where the server will be created at first
                    // startup. Get the default template server.xml instead. The server.xml for "defaultServer"
                    // will be created from this.
                    serverXML = getDefaultServerXML();
                }

                // Read server.xml file into Memory
                Document document = readServerXML(serverXML);

                addFeatures(document, "localConnector-1.0");

                writeServerXML(document, serverXML);
            }

            // Start the WebSphere Liberty Profile VM
            List<String> cmd = new ArrayList<String>();

            String os = System.getProperty("os.name").toLowerCase();

            if(os.contains("win"))
                cmd.add(containerConfiguration.getWlpHome() + "bin/server.bat");
            else
                cmd.add(containerConfiguration.getWlpHome() + "bin/server");
            cmd.add("run");
            cmd.add(containerConfiguration.getServerName());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Merge any errors with stdout
            pb.redirectErrorStream(true);

            // Process JVM args and add JAVA_TOOL_OPTIONS to pb.environment()
			List<String> javaVmArguments = parseJvmArgs(containerConfiguration.getJavaVmArguments());
            StringBuilder vmArgs = new StringBuilder("-Dcom.ibm.ws.logging.console.log.level=INFO");
            for (String javaVmArgument : javaVmArguments )
                vmArgs.append(javaVmArgumentsDelimiter)
                    .append(javaVmArgument);

            log.finer("Setting JVM arguments: " + vmArgs.toString());
            pb.environment().put(JAVA_TOOL_OPTIONS, vmArgs.toString());

            log.finer("Starting server with command: " + cmd.toString());

            wlpProcess = pb.start();

            new Thread(new ConsoleConsumer()).start();

            final Process proc = wlpProcess;

            shutdownThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (proc != null) {
                        proc.destroy();
                        try {
                            proc.waitFor();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            });
            Runtime.getRuntime().addShutdownHook(shutdownThread);

            // Wait up to 30s for the server to start
            int startupTimeout = containerConfiguration.getServerStartTimeout() * 1000;
            while (startupTimeout > 0 && serviceURL == null) {
               startupTimeout -= 500;
               Thread.sleep(500);

               // Verify that the process we're looking for is actually running
               int ev = Integer.MIN_VALUE; // exit value of the process
               IllegalThreadStateException itse = null; // Will be thrown when process is still running
               try {
                  ev = wlpProcess.exitValue();
               } catch (IllegalThreadStateException e) {
                  itse = e;
               }

               if (itse == null)
                  throw new LifecycleException("Process terminated prematurely; ev = " + ev);

               if (vmid == null)
                  // Find WebSphere Liberty Profile VMs by looking for ws-launch.jar and the name of the server
                  vmid = findVirtualMachineIdByName(containerConfiguration.getServerName());

               if (wlpvm == null && vmid != null)
                  wlpvm = VirtualMachine.attach(vmid);

               if (serviceURL == null && wlpvm != null)
                  serviceURL = getVMLocalConnectorAddress(wlpvm);
            }

            // If serviceURL is still null, we were unable to start the virtual machine
            if (serviceURL == null)
               throw new LifecycleException("Unable to retrieve connector address for localConnector of started VM");

            log.finer("vmid: " + vmid);
         }
      } catch (Exception e) {
         throw new LifecycleException("Could not start container", e);
      }

      try {
         JMXServiceURL url = new JMXServiceURL(serviceURL);
         jmxConnector = JMXConnectorFactory.connect(url);
         mbsc = jmxConnector.getMBeanServerConnection();
      } catch (IOException e) {
         throw new LifecycleException("Connecting to the JMX MBean Server failed", e);
      }

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "start");
      }
   }

    private List<String> parseJvmArgs(String javaVmArguments) {
		List<String> parsedJavaVmArguments = new ArrayList<>();
		String[] splitJavaVmArguments = javaVmArguments.split(javaVmArgumentsDelimiter);
		if (splitJavaVmArguments.length > 1) {
			for (String javaVmArgument : splitJavaVmArguments) {
				if (javaVmArgument.trim().length() > 0) {
					// remove precessing spaces
					if(javaVmArgument.startsWith(javaVmArgumentsIndicator)) {
						// vm argument without spaces
						parsedJavaVmArguments.add(javaVmArgument);
					} else {
						// space handling -> concat with the precessing argument
						String javaVmArgumentExtension = javaVmArgument;
						javaVmArgument = parsedJavaVmArguments.remove(parsedJavaVmArguments.size() - 1) + javaVmArgumentsDelimiter + javaVmArgumentExtension;
						parsedJavaVmArguments.add(javaVmArgument);
					}
				}
			}
		} else {
			parsedJavaVmArguments.add(javaVmArguments);
		}
		return parsedJavaVmArguments;
	}

   private String getVMLocalConnectorAddress(VirtualMachine wlpvm)
         throws IOException {
      String serviceURL;
      String PROPERTY_NAME = "com.sun.management.jmxremote.localConnectorAddress";
      
      serviceURL = wlpvm.getAgentProperties().getProperty(PROPERTY_NAME);

      // On some environments like the IBM JVM the localConnectorAddress is not
      // in the AgentProperties but in the SystemProperties.
      if (serviceURL == null)
         serviceURL = wlpvm.getSystemProperties().getProperty(PROPERTY_NAME);
      
      if (serviceURL == null) {
          File jmxAddr = new File(getServerOutputDir() + "/logs/state/com.ibm.ws.jmx.local.address");
          if (log.isLoggable(Level.FINER)) {
              log.info("Checking local connector address file for JMX address at " + jmxAddr.getAbsolutePath());
          }
          if (jmxAddr.exists() && jmxAddr.isFile() && jmxAddr.canRead()) {
              List<String> lines = Files.readAllLines(jmxAddr.toPath(), StandardCharsets.UTF_8);
              if (lines != null && lines.size() > 0) {
                  if (log.isLoggable(Level.FINER)) {
                      log.info("Checking local connector address file for JMX address at " + lines.get(0));
                  }
                  return lines.get(0);
              }
          }
      }

      if (log.isLoggable(Level.FINER)) {
         log.finer("service url: " + serviceURL);
      }

      return serviceURL;
   }

   private String findVirtualMachineIdByName(String serverName) {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "findVirtualMachineIdByName");
      }

      List<VirtualMachineDescriptor> vmds = VirtualMachine.list();
      for (VirtualMachineDescriptor vmd : vmds) {
         String displayName = vmd.displayName();
         if (log.isLoggable(Level.FINER)) {
            log.finer("VMD displayName: " + displayName);
            log.finer("VMD id: " + vmd.id());
         }
         if (displayName.contains(serverName) && (displayName.contains("ws-server.jar") || displayName.contains("ws-launch.jar"))) {
            // If VM's display name matches, return.
            if (log.isLoggable(Level.FINER)) {
               log.exiting(className, "findVirtualMachineIdByName", vmd.id());
            }
            return vmd.id();
         }
      }

      // Only reached when VM is not found

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "findVirtualMachineIdByName");
      }

      return null;
   }

   @Override
   public ProtocolMetaData deploy(final Archive<?> archive) throws DeploymentException
   {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "deploy");

         log.finer("Archive provided to deploy method: " + archive.toString(true));
      }

      waitForVerifyApps();

      String archiveName = archive.getName();
      String archiveType = createDeploymentType(archiveName);
      String deployName = createDeploymentName(archiveName);

      archiveDeployTimes.put(deployName, System.currentTimeMillis());

      try {
         // If the deployment is to server.xml, then update server.xml with the application information
         if (containerConfiguration.isDeployTypeXML()) {
             if (log.isLoggable(FINER)) {
                 log.finer("Deploying using server.xml");
             }
            // Throw error if deployment type is not ear, war, or eba
            if (!archiveType.equalsIgnoreCase("ear") && !archiveType.equalsIgnoreCase("war") && !archiveType.equalsIgnoreCase("eba"))
               throw new DeploymentException("Invalid archive type: " + archiveType + ".  Valid archive types are ear, war, and eba.");

            // Save the archive to disk so it can be loaded by the container.
            String appDir = getAppDirectory();
            File exportedArchiveLocation = new File(appDir, archiveName);
            archive.as(ZipExporter.class).exportTo(exportedArchiveLocation, true);

            // Read server.xml file into Memory
            Document document = readServerXML();

            // Add the archive as appropriate to the server.xml file
            addApplication(document, deployName, archiveName, archiveType);

            // Update server.xml on file system
            writeServerXML(document);
         }
         // Otherwise put the application in the dropins directory
         else {
             if (log.isLoggable(FINER)) {
                 log.finer("Deploying using dropins");
             }
            // Save the archive to disk so it can be loaded by the container.
            String dropInDir = getDropInDirectory();
            File exportedArchiveLocation = new File(dropInDir, archiveName);
            String tempDir = getDropInTempDirectory();
            File tempArchiveLocation = new File(tempDir, archiveName);
            
            // Create the archive in a temporary location, then move it to the dropins directory
            // to prevent liberty trying to start the archive before it's complete
            archive.as(ZipExporter.class).exportTo(tempArchiveLocation, true);
            Files.move(tempArchiveLocation.toPath(), exportedArchiveLocation.toPath(), StandardCopyOption.REPLACE_EXISTING);
         }

         if (log.isLoggable(FINER)) {
             log.finer("Deployment done");
         }

         waitForApplicationTargetState(new String[] {deployName}, true, containerConfiguration.getAppDeployTimeout());

         // Return metadata on how to contact the deployed application
         ProtocolMetaData metaData = new ProtocolMetaData();
         HTTPContext httpContext = new HTTPContext("localhost", getHttpPort());
         List<WebModule> modules;
         if (archive instanceof EnterpriseArchive) {
             modules = getWebModules((EnterpriseArchive) archive);
         } else if (archive instanceof WebArchive) {
             WebModule m = new WebModule();
             m.name = deployName;
             m.archive = (WebArchive) archive;
             m.contextRoot = getContextRoot(m.archive);
             modules = Collections.singletonList(m);
         } else {
             modules = Collections.emptyList();
         }
         
         // register servlets
         boolean addedSomeServlets = false;
         for (WebModule module : modules) {
             List<String> servlets = getServletNames(deployName, module);
             for (String servlet : servlets) {
                 httpContext.add(new Servlet(servlet, module.contextRoot));
                 addedSomeServlets = true;
             }
         }
         
         if (!addedSomeServlets) {
             // Urk, we found no servlets at all probably because we don't have the J2EE management mbeans
             // Make a best guess at where servlets might be. Even if the servlet names are wrong, this at
             // least allows basic URL injection to work.
             if (modules.size() == 1) {
                 // If there's only one web module, add that
                 WebModule m = modules.get(0);
                 httpContext.add(new Servlet(ARQUILLIAN_SERVLET_NAME, m.contextRoot));
             } else {
                 httpContext.add(new Servlet(ARQUILLIAN_SERVLET_NAME, deployName));
             }
         }
         
         metaData.addContext(httpContext);

         if (log.isLoggable(Level.FINER)) {
            log.exiting(className, "deploy");
         }

         return metaData;
      } catch ( DeploymentException de ) {
         // Keep any more specific raised DeploymentExceptions
         throw de;
      }
      catch (Exception e) {
         // Wrap generic exceptions as DeploymentExceptions
         throw new DeploymentException("Exception while deploying application.", e);
      }
   }

   private void waitForVerifyApps() throws DeploymentException {
      String verifyApps = containerConfiguration.getVerifyApps();

      if(verifyApps != null && verifyApps.length() > 0) {
         String[] verifyAppArray = verifyApps.split(",");
         Set<String> verifyAppSet = new HashSet<String>();

         // Trim the whitespace off each app name
         for (int i = 0; i < verifyAppArray.length; i++) {
            String appToVerify = verifyAppArray[i];
            appToVerify = appToVerify.trim();
            if(appToVerify.length() > 0) {
               verifyAppSet.add(appToVerify);
            }
         }

         int totalTimeout = containerConfiguration.getVerifyAppDeployTimeout() * verifyAppSet.size();

         waitForApplicationTargetState(verifyAppSet.toArray(new String[verifyAppSet.size()]), true, totalTimeout);
      }
   }

   private List<WebModule> getWebModules(final EnterpriseArchive ear) throws DeploymentException {
       List<WebModule> modules = new ArrayList<WebModule>();
       
       for (ArchivePath path : ear.getContent().keySet()) {
           if (path.get().endsWith("war")) {
               WebModule module = new WebModule();
               module.archive = ear.getAsType(WebArchive.class, path);
               module.name = module.archive.getName().replaceFirst("^\\/", "").replaceFirst("\\.war$", "");
               module.contextRoot = getContextRoot(ear, module.archive);
               modules.add(module);
           }
       }
       return modules;
   }
   
   /**
    * Returns the short names of all servlets deployed in the module
    * <p>
    * Attempts to use J2EE management MBeans, falls back to just returning ArquillianServletRunner for testable archives and nothing otherwise.
    */
   private List<String> getServletNames(String appDeployName, WebModule webModule) throws DeploymentException {
       try {
           // If Java EE Management MBeans are present, query them for deployed servlets. This requires j2eeManagement-1.1 feature
           Set<ObjectInstance> servletMbeans = mbsc.queryMBeans(new ObjectName("WebSphere:*,J2EEApplication=" + appDeployName + ",j2eeType=Servlet,WebModule="+webModule.name), null);
           List<String> servletNames = new ArrayList<String>();
           
           for (ObjectInstance servletMbean : servletMbeans) {
               String name = servletMbean.getObjectName().getKeyProperty("name");
               
               // Websphere uses the fully qualified servlet class as the servlet name, but arquillian just wants the simple name
               if (name.contains(".")) {
                   name = name.substring(name.lastIndexOf(".") + 1);
               }
               
               servletNames.add(name);
           }
           
           // J2EE Management MBeans aren't always available, so if we didn't find any servlets and this is a testable archive
           // it ought to contain the arquillian test servlet, which is all that most tests need to work
           if (servletNames.isEmpty() && Testable.isArchiveToTest(webModule.archive)) {
               servletNames.add(ARQUILLIAN_SERVLET_NAME);
           }
           return servletNames;
       } catch (Exception e) {
           throw new DeploymentException("Error trying to retrieve servlet names", e);
       }
   }

   private String getContextRoot(EnterpriseArchive ear, WebArchive war) throws DeploymentException {
	   org.jboss.shrinkwrap.api.Node applicationXmlNode = ear.get("META-INF/application.xml");
	   if(applicationXmlNode != null && applicationXmlNode.getAsset() != null) {
		   InputStream input = null;
		   try {
			   input = ear.get("META-INF/application.xml").getAsset().openStream();
			   Document applicationXml = readXML(input);
			   XPath xPath = XPathFactory.newInstance().newXPath();
			   XPathExpression ctxRootSelector = xPath.compile("//module/web[web-uri/text()='"+ war.getName() +"']/context-root");
			   String ctxRoot = ctxRootSelector.evaluate(applicationXml);
			   if(ctxRoot != null && ctxRoot.trim().length() > 0) {
				   return ctxRoot;
			   }
		   } catch (Exception e) {
			   throw new DeploymentException("Unable to retrieve context-root from application.xml");
		   } finally {
			   closeQuietly(input);
		   }
	   }
	   return getContextRoot(war);
   }

   private String getContextRoot(WebArchive war) throws DeploymentException {
       org.jboss.shrinkwrap.api.Node webExtXmlNode = war.get("WEB-INF/ibm-web-ext.xml");
       if(webExtXmlNode != null && webExtXmlNode.getAsset() != null) {
           InputStream input = null;
           try {
               input = war.get("WEB-INF/ibm-web-ext.xml").getAsset().openStream();
               Document webExtXml = readXML(input);
               XPath xPath = XPathFactory.newInstance().newXPath();
               XPathExpression ctxRootSelector = xPath.compile("//context-root/@uri");
               String ctxRoot = ctxRootSelector.evaluate(webExtXml);
               if(ctxRoot != null && ctxRoot.trim().length() > 0) {
                   return ctxRoot;
               }
           } catch (Exception e) {
               throw new DeploymentException("Unable to retrieve context-root from ibm-web-ext.xml");
           } finally {
               closeQuietly(input);
           }
       }
       return createDeploymentName(war.getName());
   }



	private static void closeQuietly(Closeable closable) {
		try {
			if (closable != null)
				closable.close();
		} catch (IOException e) {
			log.log(Level.WARNING, "Exception while closing Closeable", e);
		}
	}

   private int getHttpPort() throws DeploymentException {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "getHttpPort");
      }

      int httpPort = containerConfiguration.getHttpPort();

      if (httpPort == 0)
         httpPort = getHttpPortFromChannelFWMBean("defaultHttpEndpoint");

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "getHttpPort", httpPort);
      }
      return httpPort;
   }

   // Returns the HttpPort configured on the Channel Framework MBean with the provided endpoint name
   private int getHttpPortFromChannelFWMBean(String endpointName) throws DeploymentException {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "getHttpPortFromChannelFWMBean", endpointName);
      }

      ObjectName endpointMBean = null;
      try {
         endpointMBean = new ObjectName(
               "WebSphere:feature=channelfw,type=endpoint,name="
                     + endpointName);
      } catch (MalformedObjectNameException e) {
         throw new DeploymentException(
               "The generated object name is wrong. The endpointName used was '"
                     + endpointName + "'", e);
      } catch (NullPointerException e) {
         // This should never happen given that the name parameter to the
         // ObjectName constructor above can never be null
         throw new DeploymentException("This should never happen", e);
      }

      int httpPort;

      try {
         if (!mbsc.isRegistered(endpointMBean))
            throw new DeploymentException("The Channel Framework MBean with endpointName '"
                  + endpointName + "' does not exist.");

         httpPort = ((Integer)mbsc.getAttribute(endpointMBean, "Port")).intValue();
         log.finer("httpPort: " + httpPort);
      } catch (Exception e) {
         throw new DeploymentException(
               "Exception while retrieving httpPort information from Channel Framework MBean. "
               + "The httpPort can also be manually configured in the arquillian container configuration.", e);
      }

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "getHttpPortFromChannelFWMBean", httpPort);
      }
      return httpPort;
   }

   @Override
   public void undeploy(final Archive<?> archive) throws DeploymentException
   {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "undeploy");
      }

      String archiveName = archive.getName();
      String deployName = createDeploymentName(archiveName);
      String deployDir = null; // will become either app or dropin dir

      archiveDeployTimes.remove(deployName);

      try {
         // If deploy type is xml, then remove the application from the xml file, which causes undeploy
         if (containerConfiguration.isDeployTypeXML()) {
            // Read the server.xml file into Memory
            Document document = readServerXML();

            // Remove the archive from the server.xml file
            removeApplication(document, deployName);

            // Update server.xml on file system
            writeServerXML(document);

            // Wait until the application is undeployed
            waitForApplicationTargetState(new String[] {deployName}, false, containerConfiguration.getAppUndeployTimeout());
         }

         // Now we can proceed and delete the archive for either deploy type
         if (containerConfiguration.isDeployTypeXML()) {
            deployDir = getAppDirectory();
         } else {
            deployDir = getDropInDirectory();
         }

         // Remove the deployed archive
         File exportedArchiveLocation = new File(deployDir, archiveName);
         if (!containerConfiguration.isFailSafeUndeployment()) {
            try {
               if (!Files.deleteIfExists(exportedArchiveLocation.toPath())) {
                  throw new DeploymentException("Archive already deleted from deployment directory");
               }
            } catch (IOException e) {
               throw new DeploymentException("Unable to delete archive from deployment directory", e);
            }
         } else {
            try {
               Files.deleteIfExists(exportedArchiveLocation.toPath());
            } catch (IOException e) {
               log.log(Level.WARNING, "Unable to delete archive from deployment directory -> failsafe -> file marked for delete on exit", e);
               exportedArchiveLocation.deleteOnExit();
            }
         }

         // If it was the archive deletion that caused the undeploy we wait for the
         // correct state
         if (!containerConfiguration.isDeployTypeXML()) {
            // Wait until the application is undeployed
            waitForApplicationTargetState(new String[] {deployName}, false, containerConfiguration.getAppUndeployTimeout());
         }

      } catch (Exception e) {
         throw new DeploymentException("Exception while undeploying application.", e);
      }

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "undeploy");
      }
   }

   private String getDropInDirectory() throws IOException {
      String dropInDir = getServerConfigDir() +
            "/dropins";
      if (log.isLoggable(Level.FINER))
         log.finer("dropInDir: " + dropInDir);
      new File(dropInDir).mkdirs();
      return dropInDir;
   }

   private String getAppDirectory() throws IOException
   {
      String appDir = getServerConfigDir() + "/apps";
      if (log.isLoggable(Level.FINER))
         log.finer("appDir: " + appDir);
      new File(appDir).mkdirs();
      return appDir;
   }
   
   private String getDropInTempDirectory() throws IOException {
      String dropInTempDir = getServerConfigDir() + "/temp";
      if (log.isLoggable(Level.FINER))
          log.finer("dropInDir: " + dropInTempDir);
      new File(dropInTempDir).mkdirs();
      return dropInTempDir;
    }

   private String getServerXML() throws IOException
   {
      String serverXML = getServerConfigDir() + "/server.xml";
      if (log.isLoggable(Level.FINER))
         log.finer("server.xml: " + serverXML);
      return serverXML;
   }

   // templates/servers/defaultServer/server.xml
   private String getDefaultServerXML() {
      String serverXML = containerConfiguration.getWlpHome() +
                         "/templates/servers/defaultServer/server.xml";
      if (log.isLoggable(FINER)) {
         log.finer("default server.xml: " + serverXML);
      }

      return serverXML;
   }

   private String createDeploymentName(String archiveName)
   {
      return archiveName.substring(0, archiveName.lastIndexOf("."));
   }

   private String createDeploymentType(String archiveName)
   {
      return archiveName.substring(archiveName.lastIndexOf(".")+1);
   }

   private Document readServerXML() throws DeploymentException {
      try {
    	      return readServerXML(getServerXML());
      } catch (IOException e) {
          throw new DeploymentException( "Can't read server.xml", e);
      }
   }

   private Document readServerXML(String serverXML) throws DeploymentException {
	   InputStream input = null;
	   try {
		   input = new FileInputStream(new File(serverXML));
		   return readXML(input);
	   } catch (Exception e) {
		   throw new DeploymentException("Exception while reading server.xml file.", e);
	   } finally {
	       closeQuietly(input);
	   }
	}

   private Document readXML(InputStream input) throws ParserConfigurationException, SAXException, IOException {
	   DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
	   DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
	   return documentBuilder.parse(input);
   }

   private void writeServerXML(Document doc) throws DeploymentException, IOException {
       writeServerXML(doc, getServerXML());
   }

   private void writeServerXML(Document doc, String serverXML) throws DeploymentException {
      try {
         TransformerFactory tf = TransformerFactory.newInstance();
         Transformer tr = tf.newTransformer();
         tr.setOutputProperty(OutputKeys.INDENT, "yes");
         DOMSource source = new DOMSource(doc);
         StreamResult res = new StreamResult(new File(serverXML));
         tr.transform(source, res);
      } catch (Exception e) {
         throw new DeploymentException("Exception wile writing server.xml file.", e);
      }
   }

   private Element createFeature(Document doc, String featureName) {

       Element feature = doc.createElement("feature");
       feature.appendChild(doc.createTextNode(featureName));

       return feature;
   }

   private void addFeatures(Document doc, String featureNames) {
      NodeList rootList = doc.getElementsByTagName("featureManager");
      Node featureManager = rootList.item(0);

      for (String featureName : featureNames.split(",")) {
          if (!checkFeatureAlreadyThere(featureName, featureManager.getChildNodes())) {
              featureManager.appendChild(createFeature(doc, featureName));
          }
      }
   }

   private boolean checkFeatureAlreadyThere(String featureName, NodeList featureManagerList) {
       for (int i=0; i<featureManagerList.getLength(); i++) {
           Node feature = featureManagerList.item(i);
           if ("feature".equals(feature.getNodeName())) {
               Node featureText = feature.getFirstChild();
               if (featureText != null && featureText.getTextContent().trim().equals(featureName)) {
                   return true;
               }
           }
       }

       return false;
   }

   private Element createApplication(Document doc, String deploymentName, String archiveName, String type) throws DeploymentException
   {
      // create new Application
      Element application = doc.createElement("application");
      application.setAttribute("id", deploymentName);
      application.setAttribute("location", archiveName);
      application.setAttribute("name", deploymentName);
      application.setAttribute("type", type);

      // create shared library
      if (containerConfiguration.getSharedLib() != null
          ||containerConfiguration.getApiTypeVisibility() != null) {

         Element classloader = doc.createElement("classloader");

         if (containerConfiguration.getSharedLib() != null) {
            classloader.setAttribute("commonLibraryRef", containerConfiguration.getSharedLib());
         }

         if (containerConfiguration.getApiTypeVisibility() != null) {
            classloader.setAttribute("apiTypeVisibility", containerConfiguration.getApiTypeVisibility());
         }
         application.appendChild(classloader);
      }

      if(containerConfiguration.getSecurityConfiguration() != null) {
         InputStream input = null;
         try {
            input = new FileInputStream(new File(containerConfiguration.getSecurityConfiguration()));
            Document securityConfiguration = readXML(input);
            application.appendChild(doc.adoptNode(securityConfiguration.getDocumentElement().cloneNode(true)));
         } catch (Exception e) {
            throw new DeploymentException("Exception while reading " + containerConfiguration.getSecurityConfiguration() + " file.", e);
         } finally {
            closeQuietly(input);
         }
      }

      return application;
   }

   private void addApplication(Document doc, String deployName, String archiveName, String type) throws DOMException, DeploymentException
   {
      NodeList rootList = doc.getElementsByTagName("server");
      Node root = rootList.item(0);
      root.appendChild(createApplication(doc, deployName, archiveName, type));
   }

   private void removeApplication(Document doc, String deployName)
   {
      Node server = doc.getElementsByTagName("server").item(0);
      NodeList serverlist = server.getChildNodes();
      for (int i=0; serverlist.getLength() > i; i++) {
         Node node = serverlist.item(i);
         if (node.getNodeName().equals("application") && node.getAttributes().getNamedItem("id").getNodeValue().equals(deployName)) {
            node.getParentNode().removeChild(node);
         }
      }
   }

   private void logAllApps() {
      try {
         log.info("Listing all apps...");
         Set<ObjectInstance> allApps = mbsc.queryMBeans(null, null);
         log.info("Size of results: " + allApps.size());
         for (ObjectInstance app : allApps) {
            log.info(app.getObjectName().toString());
         }
      } catch(IOException e) {
         log.warning("Could not print list of all apps. Exception thrown is: " + e.getMessage());
      }
   }

   private void waitForApplicationTargetState(String[] applicationNames, boolean targetState, int timeout) throws DeploymentException {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "waitForApplicationTargetState");
         StringBuilder sb = new StringBuilder();
         sb.append("Waiting for apps to ").append(targetState ? "start" : "stop").append(" - ");
         for (String appName : applicationNames) {
             sb.append(appName).append(", ");
         }
      }
      
      String desiredState = targetState ? "STARTED" : "NOT_INSTALLED";
      List<AppStateChecker> appStateCheckers = new ArrayList<>();
      
      for (String appName : applicationNames) {
          appStateCheckers.add(new AppStateChecker(mbsc, appName));
      }
      
      boolean allReady;
      int timeleft = timeout * 1000;
      
      do {
          allReady = true;
          
          for (AppStateChecker appStateChecker : appStateCheckers) {
              String currentState = appStateChecker.checkState();
              
              if (log.isLoggable(FINER)) {
                  log.finer("AppMBean for " + appStateChecker.getAppName() + " is in state " + currentState);
              }
              
              if (!currentState.equals(desiredState)) {
                  allReady = false;
              }
              
              // Applications should only be in INSTALLED state for a very short time before moving to STARTING
              // An application which stays in INSTALLED state has failed to start
              if (desiredState.equals("STARTED") && currentState.equals("INSTALLED") && appStateChecker.getMsInState() >= 1000) {
                  log.finer(appStateChecker.getAppName() + " has failed to start (in INSTALLED state for more than one second)");
                  throwDeploymentException(appStateChecker.getAppName());
              }
          }
          
          if (allReady) {
              break;
          }
          try {
              Thread.sleep(100);
          } catch (InterruptedException e) {
              throw new DeploymentException("Deployment interrupted", e);
          }
          timeleft -= 100;
      } while (timeleft > 0);
      
      if (!allReady) {
          // We're about to get the status of apps one more time to build the error message
          // but there's a possibility they've all finished between now and the last time we checked
          // so we reset allReady and compute it again as we loop through the apps
          allReady = true;
          
          StringBuilder appMessageStatus = new StringBuilder();
          for (AppStateChecker appStateChecker : appStateCheckers) {
              String actualState = appStateChecker.checkState();
              if (!actualState.equals(desiredState)) {
                  appMessageStatus.append("Timeout while waiting for \"")
                          .append(appStateChecker.getAppName())
                          .append("\" ApplicationMBean to reach ")
                          .append(desiredState)
                          .append(". Actual state: ")
                          .append(actualState)
                          .append(".");
                  allReady = false;
              }
          }
          
          if (allReady == false) {
              logAllApps();
              throw new DeploymentException(appMessageStatus.toString());
          }
      }

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "waitForApplicationTargetState");
      }
   }

   public void stop() throws LifecycleException
   {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "stop");
      }

      try {
         jmxConnector.close();
      } catch (IOException e) {
         throw new LifecycleException("Communication with the MBean Server failed.", e);
      }

      if (shutdownThread != null) {
         Runtime.getRuntime().removeShutdownHook(shutdownThread);
         shutdownThread = null;
      }
      try {
         if (wlpProcess != null) {
            wlpProcess.destroy();
            wlpProcess.waitFor();
            wlpProcess = null;
         }
      } catch (Exception e) {
         throw new LifecycleException("Could not stop container", e);
      }

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "stop");
      }
   }

   public ProtocolDescription getDefaultProtocol() {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "getDefaultProtocol");
      }

      String defaultProtocol = "Servlet 3.0";

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "getDefaultProtocol", defaultProtocol);
      }

      return new ProtocolDescription(defaultProtocol);
   }

   @Override
   public Class<WLPManagedContainerConfiguration> getConfigurationClass() {
      return WLPManagedContainerConfiguration.class;
   }

   public void deploy(Descriptor descriptor) throws DeploymentException {
      // TODO Auto-generated method stub

   }

   public void undeploy(Descriptor descriptor) throws DeploymentException {
      // TODO Auto-generated method stub

   }

   /**
    * Fetch a liberty ENV var. The sources have the following precedence: 1 -
    * server specific server.env 2 - system wide server.env 3 - shell environment
    *
    * @param key
    * @return ENV var value or null
    * @throws IOException
    */
   private String getLibertyEnvVar(String key) throws IOException {
      String value = null;
      Properties props = new Properties();
      InputStream fisServerEnv = null;
      InputStream fisSystemServerEnv = null;

      try {
         // Server specific
         if (!key.equals(WLP_USER_DIR)) { // WLP_USER_DIR can be specified only as a process environment variable
                                          // or ${wlp.install.dir}/etc/server.env file
            try {
               fisServerEnv = new FileInputStream(new File(getServerEnvFilename()));
               props.load(fisServerEnv);
               value = props.getProperty(key);
            } catch (FileNotFoundException fnfex) {
               // We ignore FileNotFound here
            }
         }
         // Liberty system wide not used for things that would collide across >1 server like LOG_DIR
         if (value == null && !key.equals(LOG_DIR)) {
            try {
               fisSystemServerEnv = new FileInputStream(new File(getSystemServerEnvFilename()));
               props.load(fisSystemServerEnv);
               value = props.getProperty(key);
            } catch (FileNotFoundException fnfex) {
               // We can safely ignore FileNotFound
            }
            // Process environment variables
            if (value == null) {
               value = getEnv(key);
            }
         }

         log.fine("server.env: " + key + "=" + value);
         return value;

      } finally {
         closeQuietly(fisServerEnv);
         closeQuietly(fisSystemServerEnv);
      }
   }

   /**
    * Enable @ShouldThrowExceptions in tests by looking for messages that indicate
    * the cause of DeploymentExceptions.
    *
    * @param applicationName
    * @throws IOException
    * @throws DeploymentException
    */
   private void throwDeploymentException(String applicationName) throws DeploymentException {
      BufferedReader br = null;
      String messagesFilePath = null;

      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "throwDeploymentException");
      }

      try {
         messagesFilePath = getMessageFilePath();
         log.finest("Scanning message file " + messagesFilePath);

         br = new BufferedReader(new InputStreamReader(new FileInputStream(messagesFilePath)));
         String bestLine = null;

         /*
          * Process the logs to see what the cause of the exception was. If the exception is a CDI
          * DeploymentException or DefinitionException, wrap it in an Arquillian DeploymentException
          * and throw it to Arquillian.
          */
         /*
          * Find the last line where our application either starts or fails to start.
          * We look for the last line in case a test deploys several apps with the same name, we want the most recent.
          */
         String line;
         while ((line = br.readLine()) != null) {
             if ( (line.contains("CWWKZ0002") || line.contains("CWWKZ0001I")) && line.contains(applicationName)) {
                 bestLine = line;
             }
         }
         
         /*
          * Having found the line, find what the cause of the exception was. If the exception is a CDI
          * DeploymentException or DefinitionException, wrap it in an Arquillian DeploymentException
          * and throw it to Arquillian.
          */
         if (bestLine == null) {
             /* The error message may not have showed up in the log yet. Check again on next pass. */
             log.finest("The application deployment failure message was not found. Waiting...");
         } else if (bestLine.contains("CWWKZ0002")) {
            StringBuilder sb = new StringBuilder();
            sb.append("Failed to deploy ")
                  .append(applicationName)
                  .append(" on ")
                  .append(containerConfiguration.getServerName());

            Throwable ffdcXChain = getFfdcWithNestedCauseChain(applicationName);

            if (bestLine.contains("DefinitionException")) {
               log.finest("DefinitionException found in line" + bestLine + " of file " + messagesFilePath);
               DefinitionException cause = new javax.enterprise.inject.spi.DefinitionException(bestLine, ffdcXChain);
               throw new DeploymentException(sb.toString(), cause);
            } else if (bestLine.contains("DeploymentException") ||
                       bestLine.contains("InconsistentSpecializationException") ||
                       bestLine.contains("UnserializableDependencyException")) {
               /*
                * The CDI specification allows an implementation to throw a subclass of
                * javax.enterprise.inject.spi.DeploymentException. Weld has three types
                * such exceptions:
                *  - org.jboss.weld.exceptions.DeploymentException
                *  - org.jboss.weld.exceptions.InconsistentSpecializationException
                *  - org.jboss.weld.exceptions.UnserializableDependencyException
                */
               log.finest("DeploymentException found in line" + bestLine + " of file " + messagesFilePath);
               javax.enterprise.inject.spi.DeploymentException cause = new javax.enterprise.inject.spi.DeploymentException(bestLine, ffdcXChain);
               throw new DeploymentException(sb.toString(), cause);
            } else {
               /*
                * Application failed to deploy due to some other exception.
                */
               String exceptionFound = bestLine.substring(bestLine.indexOf("The exception message was: ") + 27);
               sb.append(": ");
               sb.append(exceptionFound);
               log.finest("A exception was found in line " + bestLine + " of file " + messagesFilePath);
               throw new DeploymentException(sb.toString(), ffdcXChain);
            }
         } else if (bestLine.contains("CWWKZ0001I") && bestLine.contains(applicationName)) {
            throw new DeploymentException("Application " + applicationName +
                  " started unexpectedly even though it never reached the STARTED state. This should never happen.");
         }
      } catch (IOException e) {
         log.warning("Exception while reading messages.log: " + messagesFilePath + ": " + e.toString());
         e.printStackTrace();
      } catch (XPathExpressionException e) {
        log.warning(e.getMessage());
      } finally {
         closeQuietly(br);
      }

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "throwDeploymentException");
      }
   }
   
   /**
    * This method is called when we see a CWWKZ0002 in the messages log. Its job is
    * to return an Exception with the causal chain of exceptions that is expressed
    * in the FFDC log file for the application. It it written to use only Java 6
    * methods.
    * 
    * @param applicationName
    * @return the exception chain
    */
   private Throwable getFfdcWithNestedCauseChain(String appName) {

       // Get the set of ffdc files
       File[] ffdcFiles = getFfdcFilesSince(archiveDeployTimes.get(appName));

       // Get StateChangeException FFDC file for this application
       File ffdc = findStateChangeExceptionFfdcFileForApp(appName, ffdcFiles);

       Throwable cause = null;

       if (ffdc != null) {
           // Get StateChangeException ffdc exception names and info chain
           ArrayList<ExMsg> chain = getExceptionCausalChain(ffdc);

           if (chain != null) {
               cause = buildNestedException(chain);
           }
       }

       for (Throwable c = cause; c != null; c = c.getCause()) {
           log.finest("FFDC Exception Chain:" + c.getClass());
       }

       return cause;

   }

   /**
    * Create and return the nested FFDC exception chain as a Throwable with
    * cause(s)
    * 
    * @param exs
    *            - an ordered list of the Exception log messages
    * @return a Throwable with usable causal chain
    */
   private Throwable buildNestedException(ArrayList<ExMsg> exs) {

       Throwable causeSoFar = null;

       for (int nestLevel = exs.size() - 1; nestLevel >= 0; nestLevel--) {
           causeSoFar = createException(exs.get(nestLevel), causeSoFar);
       }

       return causeSoFar;
   }

   /**
    * Turn a FFDC log line into a Throwable object. This depends on Class.forname
    * loading so maven pom dependencies need to include classes that need to be
    * loaded on the classpath
    * 
    * @param x - the exception message object
    * @param cause - something to nest inside the Throwable we create - can be null
    * @return - a non-null Throwable that reflects the log Line as well as
    *           possible. Note that fully 'private' classes are unlikely to be in
    *           tests' @ShouldThrow tests anyway - so in that case the important 
    *           thing is to not break the causal chain with this link. 
    *           We may have private subclasses of public Exceptions - we don't handle, 
    *           but these should be treated as special cases, handled elsewhere - for
    *           example with a service loaded DeploymentExceptionTransformer.
    */
   private Throwable createException(ExMsg x, Throwable cause) {

       Class<?> clazz = null;

       // Can we load the server's exception class here in the container?
       try {
           clazz = Class.forName(x.exName);
           log.info("Class loaded ok for name " + x.exName + " " + clazz.getName());

       } catch (ClassNotFoundException e1) {
           log.warning(
                   "Unable to load a class for: " + x.exName + " switching to " 
                   + UnloadableLogError.class.getName());
           clazz = UnloadableLogError.class;
       }

       // Is the class a valid link in an exception chain
       if (clazz != null && !Throwable.class.isAssignableFrom(clazz)) {
           log.warning("Loadable class: " + clazz.getName() + " from  " + x 
                   + " is not assignable to Throwable.");
           clazz = UnloadableLogError.class;
       }

       Throwable thisServerThrowable = null;

       log.finest("Attempting to load Throwable: " + clazz.getName());
       try {

           Constructor<?> xCon = null;
           if (cause == null) {
               try {
                   // Ideally we want to add the FFDC log line
                   xCon = clazz.getConstructor(String.class);
                   thisServerThrowable = (Throwable) xCon.newInstance(new Object[] { x.logMsg });

               } catch (NoSuchMethodException e) {
                   try {
                       // We could use a null cause
                       xCon = clazz.getConstructor(String.class, Throwable.class);
                       thisServerThrowable = (Throwable) xCon.newInstance(new Object[] { x.logMsg, null });

                   } catch (NoSuchMethodException e2) {
                       try {
                           // We could even embed the log line via a fake cause:
                           // throwable.initCause(new RuntimeException(message))
                           xCon = clazz.getConstructor();
                           thisServerThrowable = (Throwable) xCon.newInstance();
                           thisServerThrowable.initCause(new RuntimeException(x.logMsg));

                       } catch (NoSuchMethodException e3) {
                           log.warning("Arquillian container could not load external Throwable 1, from: " + x);
                           thisServerThrowable = new UnloadableLogError(x.logMsg);
                       }
                   }
               }
           } else { // Cause is not null
               try {
                   // Ideally we want to add the FFDC log line
                   xCon = clazz.getConstructor(String.class, Throwable.class);
                   thisServerThrowable = (Throwable) xCon.newInstance(new Object[] { x.logMsg, cause });

               } catch (NoSuchMethodException e) {
                   try {
                       // Some 'old' errors can add the cause afterwards
                       xCon = clazz.getConstructor(String.class);
                       thisServerThrowable = (Throwable) xCon.newInstance(new Object[] { x.logMsg });
                       thisServerThrowable.initCause(cause);

                   } catch (NoSuchMethodException e2) {
                       try {
                           // We are prepared to loose the log line to get the correct exception
                           xCon = clazz.getConstructor(Throwable.class);
                           thisServerThrowable = (Throwable) xCon.newInstance(new Object[] { cause });

                       } catch (NoSuchMethodException e3) {
                           // We could not get the right result;
                           log.warning("Arquillian container could not load external Throwable 2, from: " + x);
                           thisServerThrowable = new UnloadableLogError(x.logMsg, cause);
                       }
                   }
               }

           }

       } catch (InstantiationException e) {
           log.warning(e.toString() + "Arquillian container could not load external Throwable 3, from: " + x);
       } catch (IllegalAccessException e) {
           log.warning(e.toString() + "Arquillian container could not load external Throwable 4, from: " + x);
       } catch (IllegalArgumentException e) {
           log.warning(e.toString() + "Arquillian container could not load external Throwable 5, from: " + x);
       } catch (InvocationTargetException e) {
           log.warning(e.toString() + "Arquillian container could not load external Throwable 6, from: " + x);
       }

       if (thisServerThrowable == null) {
           thisServerThrowable = new UnloadableLogError(x.logMsg, cause);
       }

       log.finest("Actually created class= " + thisServerThrowable.getClass().getName() + " instance msg="
               + thisServerThrowable.getMessage());

       return thisServerThrowable;
   }

   /**
    * Get a list of ExMsg objects representing Exceptions in a FFDC log file
    * 
    * @param ffdc
    *            the ffdc file being processed
    * @return an ordered list of exceptions, as ExMsg, foundß
    */
   private ArrayList<ExMsg> getExceptionCausalChain(File ffdc) {

       ArrayList<ExMsg> result = new ArrayList<ExMsg>();

       BufferedReader br = null;
       try {
           br = new BufferedReader(new InputStreamReader(new FileInputStream(ffdc)));
           String line;
           // FFDC files have the form:
           // Stack Dump = com.ibm.ws.container.service.state.StateChangeException:
           // Caused by: org.jboss.weld.exceptions.WeldException:
           // Caused by: org.jboss.weld.exceptions.DefinitionException:

           while ((line = br.readLine()) != null) {
               Pattern p = Pattern.compile("(Stack Dump = |Caused by: )([\\p{L}\\p{N}_$\\.]+?):(.*)");

               Matcher m = p.matcher(line);
               if (m.matches()) {
                   ExMsg x = new ExMsg();
                   x.exName = m.group(2);
                   x.logMsg = line;
                   log.finest("match on line: " + line + " resolved to className " + x.exName);
                   result.add(x);
               } else {
                   log.finest("no match found on line: " + line);
               }
           }

       } catch (IOException e) {
           // We are OK to return empty handed (and fail the test if needed) but will log the event...
           log.warning("FFDC file " + ffdc!=null?ffdc.getAbsolutePath():"null" + " IO Exception: " + e.toString());
       } finally {
           if (br != null) {
               try {
                   br.close();
               } catch (IOException e) {
                   log.warning(e.getMessage());
               }
           }
       }
       log.finest("Exception stack found was: " + Arrays.deepToString(result.toArray()));
       return result;
   }

   /**
    * Find the app's StateChangeException FFDC file (using Java 6 compatible code).
    * 
    * @param appName
    * @param ffdcFiles
    * 
    * @return The correct FFDC File or null
    * 
    * @throws FileNotFoundException
    * @throws IOException
    */
   private File findStateChangeExceptionFfdcFileForApp(String appName, File[] ffdcFiles) {

       BufferedReader br = null;
       try {
           for (int i = 0; i < ffdcFiles.length; i++) {

               // Set up a bufferedReader
               File ffdcFile = ffdcFiles[i];
               log.finest("Processing FFDC file: " + ffdcFile.getAbsolutePath());
               try {
                   br = new BufferedReader(new InputStreamReader(new FileInputStream(ffdcFile)));
               } catch (FileNotFoundException e) {
                   log.warning("File " + ffdcFile.getAbsolutePath() + " is no longer accessible");
                   continue; // File has been deleted but it might not have been the target one
               }

               // See if it fulfills the criteria
               try {

                   boolean fileIsForStateChangeException = false;
                   boolean fileIsForCorrectApplication = false;

                   String line;
                   while ((line = br.readLine()) != null) {

                       fileIsForStateChangeException = fileIsForStateChangeException || line
                               .contains("Stack Dump = com.ibm.ws.container.service.state.StateChangeException");
                       log.finest("fileIsForStateChangeException:" + fileIsForStateChangeException + " " + line);

                       fileIsForCorrectApplication = fileIsForCorrectApplication
                               || line.contains("appName") && line.contains(appName);
                       log.finest("fileIsForCorrectApplication:" + fileIsForCorrectApplication + "(" + appName + ")"
                               + " " + line);

                       // Have we found all the criteria we are looking for?
                       if (fileIsForStateChangeException && fileIsForCorrectApplication) {
                           // We have found what we are looking for
                           log.finest("Found StateChangeExceptionFfdc for " + appName + ": "
                                   + ffdcFile.getAbsolutePath());
                           return ffdcFile;
                       }

                   }
                   log.finest("Did not find StateChangeExceptionFfdc for " + appName + ": "
                           + ffdcFile.getAbsolutePath() + "(file was scanned completely)");

               } catch (IOException e) {
                   log.warning("File " + ffdcFile.getAbsolutePath() + " is not readable");
                   continue; // File has perhaps deleted but not necessarily a disaster if we find what we
                             // need later
               }
           }

       } finally {
           if (br != null) {
               try {
                   br.close();
               } catch (IOException e) {
                   log.warning(e.getMessage());
               }
           }
       }

       // We did not find what we are looking for
       log.finest("No StateChangeExceptionFfdc for " + appName + " was found ");
       return null;
   }

   /**
    * Get the set of FFDC files, we are only called when we are expecting to see
    * some FFDC's so we will loop, waiting for 1/100th of a second and up to 10
    * seconds until we see at least on FFDC. Normally we will not have to wait at
    * all.
    * 
    * @return an array of File from the FFDC dir that start "ffdc_"
    * @throws IOException
    */
   private File[] getFfdcFilesSince(Long deployTime) {

       // We want to look for all FFDCs from at least 1 second before deploy until
       // now...
       final long AT_LEAST_ONE_SECOND = (1 * 1000) + 1;
       // Perhaps we have not passed in the deploy time - if so scan all FFDCs:
       final long since = deployTime != null ? (deployTime - AT_LEAST_ONE_SECOND) : 0;
       File[] ffdcFiles = null;

       int attempts = 0;
       do {
           try {
               Thread.sleep(100);
           } catch (InterruptedException e1) {
               // do nothing
           }
           File ffdcDir = null;
           String ffdcDirPath = "NOT_SET";
           try {
               ffdcDirPath = getLogsDirectory() + "/ffdc";
               ffdcDir = new File(ffdcDirPath);
           } catch (IOException e) {
               log.warning("FFDC path : " + ffdcDirPath + " not (yet?) openable as new File");
               continue;
           }
           log.finest(
                   "FFDC Dir: " + ffdcDir.getAbsolutePath() + "looking for ffdc_* file since " + new Timestamp(since));
           ffdcFiles = ffdcDir.listFiles(new FilenameFilter() {
               public boolean accept(File dir, String fileName) {
                   File ffdcFile = new File(dir, fileName);
                   return (ffdcFile.lastModified() >= since) && fileName.startsWith("ffdc_");
               }
           });
           attempts++;
           log.finest("FFDC files are: " + Arrays.toString(ffdcFiles));

       } while ((ffdcFiles == null || ffdcFiles.length == 0) && attempts <= 100);

       return ffdcFiles;
   }

   /**
    * Do System.getenv under a doPrivileged
    * @param name
    * @return
    */
   private String getEnv(final String name) {
      final String result = AccessController.doPrivileged(
         new PrivilegedAction<String>() {
            @Override
            public String run() {
               return System.getenv(name);
            }
         });
      return result;
   }

   /**
    * Get wlp/usr taking account of user preferences
    *
    * @return wlp.user.dir
    * @throws IOException
    */
   private String getWlpUsrDir() throws IOException {
      String usrDir = getLibertyEnvVar(WLP_USER_DIR);
      if (usrDir == null) {
         usrDir = containerConfiguration.getWlpHome() + "/usr/";
      }
      log.finer("wlp.usr.dir path: " + usrDir);
      return usrDir;
   }

   /**
    * Get server output dir taking account of user preferences
    *
    * @return server.output.dir
    * @throws IOException
    */
   private String getServerOutputDir() throws IOException {
      String serverOutputDir = null;
      String wlpOutputDir = getLibertyEnvVar(WLP_OUTPUT_DIR);
      if (wlpOutputDir == null) {
         serverOutputDir = getServerConfigDir(); // Output dir defaults to Config dir
      } else {
         serverOutputDir = wlpOutputDir + "/" + containerConfiguration.getServerName();
      }
      log.finer("server output dir path: " + serverOutputDir);
      return serverOutputDir;
   }

   /**
    * Get server config dir (where server.xml etc. usually are)
    * @return server.config.dir
    * @throws IOException
    */
   private String getServerConfigDir() throws IOException {
	   String serverConfigDir = getWlpUsrDir() + "servers/" + containerConfiguration.getServerName();
	   log.finer("server.config.dir path: " + serverConfigDir);
	   return serverConfigDir;
   }

   /**
    * Get logs directory taking account of user preferences
    *
    * @return server.output.dir/logs
    * @throws IOException
    */
   private String getLogsDirectory() throws IOException {
      String logDir = null;

      // 1 - from server.xml/server/logging/@logDirectory
      try {
         logDir = getServerXmlLoggingAttribute(LOG_DIRECTORY);
         log.finest("logDir getServerXmlLoggingAttribute: " + logDir);
      } catch (DeploymentException e) {
         // let logDir stay null
         log.warning(e.getMessage());
      } catch (XPathExpressionException e) {
         log.warning(e.getMessage());
      }

      // 2 - bootstrap.properties: com.ibm.ws.logging.log.directory
      if(logDir==null || logDir.length()==0) {
         logDir = getBootstrapProperty(LOG_DIRECTORY_PROPERTY);
         log.finest("logDir getBootstrapProperty(LOG_DIRECTORY_PROPERTY): " + logDir);
      }

      // 3 - Environment variable ${LOG_DIR}
      if (logDir == null || logDir.length()==0) {
         logDir = getLibertyEnvVar(LOG_DIR);
         log.finest("logDir getLibertyEnvVar: " + logDir);
      }

      // 4 - Default location e.g. "wlp/usr/<serverName>/logs"
      if (logDir == null || logDir.length()==0) {
            logDir = getServerOutputDir() + "/logs";
            log.finest("logDir getServerOutputDir: " + logDir);
      }

      log.finest("getLogsDirectory result: " + logDir);
      return logDir;
   }

   /**
    * Get a logging related attribute out of the server.xml
    *
    * @param attr
    * @return
    * @throws XPathExpressionException
    * @throws DeploymentException
    */
   private String getServerXmlLoggingAttribute(String attr) throws XPathExpressionException, DeploymentException{
      String loggingElementXpath = "/server/logging";
      String resultString = "";
      try {
         Document serverXml = readServerXML();
         XPathFactory xPathFactory = XPathFactory.newInstance();
         XPath xpath = xPathFactory.newXPath();
         Element loggingElement = null;
         loggingElement = (Element) xpath.evaluate(loggingElementXpath, serverXml, XPathConstants.NODE);
         if( loggingElement != null && loggingElement.hasAttribute(attr) ) {
            resultString = loggingElement.getAttribute(attr);
         }else {
            log.finest("logging element is null for " + loggingElementXpath + "/@" + attr );
         }
      } catch (XPathExpressionException e) {
         e.printStackTrace();
         log.finer("problem with expression: " + loggingElementXpath + " " + e.getMessage());
         throw e;
      } catch (DeploymentException e) {
         e.printStackTrace();
         log.finer("unreadable server.xml"  + e.getMessage());
         throw e;
      }

      log.finest("getServerXmlLoggingAttribute("  + attr + ")=" + resultString);
      return resultString;
   }

/**
   * Get location of the server.env file
   *
   * @return server.output.dir/logs
   * @throws IOException
   */
   private String getServerEnvFilename() throws IOException
   {
      String serverEnv = getServerConfigDir() + "/server.env";
      log.finer("server.env path: " + serverEnv);
      return serverEnv;
   }

   /**
    * Users can create system wide environment variables
    *
    * @return ${wlp.install.dir}/etc/server.env
    */
   private String getSystemServerEnvFilename()
   {
      String systemServerEnv = containerConfiguration.getWlpHome() + "/etc/server.env";
      log.finer("system wide server.env path: " + systemServerEnv);
      return systemServerEnv;
   }
   
	private List<String> getJPMSOptions() {
		List<String> args = new ArrayList<String>();
		File jpmsOptions = new File(containerConfiguration.getWlpHome(), "lib/platform/java/java9.options");
		try (Scanner s = new Scanner(jpmsOptions)) {
			String line = null;
			String arg = null;
			while(s.hasNextLine()) {
				line = s.nextLine().trim();
				// the java9.options file is of the format:
				// # Optional comment
				// --add-exports
				// jdk.management.agent/jdk.internal.agent=ALL-UNNAMED
				if(line.startsWith("#")) { 
					continue; // skip over commented out lines
				} else if(line.startsWith("--")) {
					arg = line;
				} else {
					args.add(arg + '=' + line);
					arg = null;
				}
			}
		} catch (IOException e) {
			log.finer("Unable to locate Liberty JPMS options file at: " + jpmsOptions.getAbsolutePath());
			return args;
		}
		return args;
	}

   /**
    * Get the path of the messages.log file
    *
    * @return
    * @throws XPathExpressionException
    * @throws DeploymentException
    * @throws IOException
    */
   private String getMessageFilePath() throws XPathExpressionException, DeploymentException, IOException {
      String messagesFilePath;

      // We assume server.xml will have been read by the time we want to do any deploys so that takes precedence
      String msgFileName = getServerXmlLoggingAttribute(MESSAGE_FILE_NAME);
      if (msgFileName == null || msgFileName.length() == 0) {
         msgFileName = getBootstrapProperty(MESSAGE_FILE_PROPERTY);
         if (msgFileName == null || msgFileName.length() == 0) {
            msgFileName = DEFAULT_MESSAGES_LOG_NAME;
         }
      }

      messagesFilePath = getLogsDirectory() + "/" + msgFileName;
      log.finer("using message.log file path: " + messagesFilePath);

      return messagesFilePath;
   }

   /**
    * Get a bootstrap.properties property
    * @param name
    */
   private String getBootstrapProperty(String key) {
      Properties props = new Properties();
      FileInputStream fisBootstrapProperties = null;
      try {
         fisBootstrapProperties = new FileInputStream(getBootstrapPropertiesPath());
         props.load(fisBootstrapProperties);
      } catch (IOException ex) {
         log.finest(ex.getMessage());
      } finally {
         closeQuietly(fisBootstrapProperties);
      }
      String value=props.getProperty(key);
      log.finest("bootstrap.properties:" + key + "=" + value);
      return value;
   }

   /**
    * Get the FilePath of the bootstrap.properties file
    * @param messageFileProperty
    * @return
    */
   private String getBootstrapPropertiesPath() {
         String bootstrapProperties = null;
         try {
            bootstrapProperties = getServerConfigDir() + "/bootstrap.properties";
         } catch (IOException e) {
            log.warning(e.getMessage());
         }
         log.finest("bootstrap.properties: " + bootstrapProperties);
         return bootstrapProperties;
   }

   /**
    * Simple class to store the metadata for a web module
    */
   private static class WebModule {
       private String name;
       private String contextRoot;
       private WebArchive archive;
   }

   /**
    * Runnable that consumes the output of the process. If nothing consumes the output the process will hang on some platforms
    * Implementation from wildfly's ManagedDeployableContainer.java
    *
    * @author Stuart Douglas
    */
   private class ConsoleConsumer implements Runnable {

       @Override
       public void run() {
           final InputStream stream = wlpProcess.getInputStream();
           final boolean writeOutput = containerConfiguration.isOutputToConsole();

           try {
               byte[] buf = new byte[32];
               int num;
               // Do not try reading a line cos it considers '\r' end of line
               while ((num = stream.read(buf)) != -1) {
                   if (writeOutput)
                       System.out.write(buf, 0, num);
               }
           } catch (IOException e) {
           }
       }

   }
   
   /*
    * When we can't find/load a class from what we think is the
    * exception name we use a generic wrapper to allow us to
    * see the log line later
    */
   public class UnloadableLogError extends RuntimeException {
       private static final long serialVersionUID = 1L;
       private String logLine;
       private Throwable cause;
       
       public UnloadableLogError(String logLine){
           this.logLine=logLine;
       }
       public UnloadableLogError(String logLine, Throwable cause){
           super(logLine, cause);
           this.logLine=logLine;
           this.cause = cause;
       }
       
   }

   /**
    * Small class the hold an potential nested exception
    */
   private class ExMsg {
       String exName;
       String logMsg;
       public String toString(){
           return exName + "(" + logMsg + ")";
       }
   }

}
