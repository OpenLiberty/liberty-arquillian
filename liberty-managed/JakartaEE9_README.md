# Arquillian Liberty Managed with Jakarta EE 9, 10 and 11

An Arquillian container adapter (`DeployableContainer` implementation) that can start and stop a local Liberty process and run tests on it over a remote protocol (effectively in a different JVM). 

## Prerequisites

**Prerequisite Version**

This `DeployableContainer` has been tested with the latest release of Open Liberty. Requires Jakarta EE 9, 10 or 11.

For Java EE 8 projects and below, check out the documentation [here](README.md).

For Jakarta EE 9 projects using Java SE 8, you will need to use the 2.x versions of the Liberty Arquillian plugin.

**Prerequisite Configuration**

The following features are required in the `server.xml` of the Liberty server.

```
<!-- Enable features -->
<featureManager>
    <feature>pages-3.0</feature>
    <feature>localConnector-1.0</feature>
    <feature>usr:arquillian-support-jakarta-3.0</feature> <!-- Optional, needed for reliable reporting of correct DeploymentExceptions -->
</featureManager>
```

or

```
<!-- Enable features -->
<featureManager>
    <feature>restfulWS-3.0</feature>
    <feature>localConnector-1.0</feature>
    <feature>usr:arquillian-support-jakarta-3.0</feature> <!-- Optional, needed for reliable reporting of correct DeploymentExceptions -->
</featureManager>
```

Read more about configuring the `arquillian-support-jakarta-3.0` feature [here](../liberty-support-feature/JakartaEE9_README.md).

You will also need to enable the `applicationMonitor` MBean support in your `server.xml`:

```
<applicationMonitor updateTrigger="mbean"/>
```

If you need a sample server.xml, please refer to the [one in our source repository](https://github.com/OpenLiberty/liberty-arquillian/blob/main/liberty-managed/src/test/resources/server.xml).

## Configuration

Default Protocol: Servlet 5.0 or REST 3.0 depend on configuration

To enable Arquillian Liberty Managed in your project, add the following to your `pom.xml`:

```xml
<dependencyManagement>
	<dependencies>
		<dependency>
			<groupId>org.jboss.arquillian</groupId>
			<artifactId>arquillian-bom</artifactId>
			<version>1.9.1.Final</version>
			<scope>import</scope>
			<type>pom</type>
		</dependency>
	</dependencies>
</dependencyManagement>

<dependencies>
	...
	<dependency>
		<groupId>io.openliberty.arquillian</groupId>
		<artifactId>arquillian-liberty-managed-jakarta</artifactId>
		<version>3.0.0</version>
		<scope>test</scope>
	</dependency>
	...
</dependencies>
```

**Container Configuration Options**

| Name | Type | Default | Description |
| ---- | ---- | ------- | ----------- |
| wlpHome | String | None | Home directory of the WLP runtime. |
| serverName | String | defaultServer | Name of the server to start. |
| httpPort | Integer | 9080 | HTTP Port of the server.  |
| javaVmArguments | String | None | JVM Arguments to pass into the WLP runtime.  |
| deployType | String | dropins | Type of deployment (available: dropins or xml) |
| sharedLib | String | None | ID of the shared library reference; if provided it will be used as the commonLibraryRef attribute of the application/classloader configuration element |
| securityConfiguration | String | None | When using xml deployType the referred file will be embedded within the application tag into the server.xml and should be used to configure the security settings. |
| failSafeUndeployment | String | None | In some scenarios the xml deployType deployments might fail to undeploy because of an open FileHandle on Windows when using an Oracle JVM. Enabling this flag suppresses the exception to be thrown and marks the file to be delete upon exiting the JVM process. |
| apiTypeVisibility | String | None | String that will be set as the apiTypeVisibility attribute of the application/classloader configuration element. See the [WLP Knowledge Center](https://www.ibm.com/support/knowledgecenter/en/SSEQTP_8.5.5/com.ibm.websphere.wlp.doc/ae/twlp_classloader_3p_apis.html) for possible values and default used when this value is not provided. |
| verifyApps | String | None | Specifies a comma-separated list of names of applications that will be verified to be started before tests are executed |
| verifyAppDeployTimeout | Integer | 20 | Time in seconds to wait for the verifyApps application deployment to complete and the applications to start |
| serverStartTimeout | Integer | 30 | Time in seconds to wait for the application server to start |
| serverStopTimeout | Integer | 30 | Time in seconds to wait for the application server to stop |
| appDeployTimeout | Integer | 20 | Time in seconds to wait for the application deployment to complete and the application to start |
| appUndeployTimeout | Integer | 2 | Time in seconds to wait for the application undeployment to complete |
| allowConnectingToRunningServer | Boolean | false | Allow a connection to be made to an already running application server process |
| outputToConsole | Boolean | true | When enabled output from the application server process will be emitted to stdout |
| fileDeleteRetries | Integer | 30 | How many times to attempt deleting a file |
| standardFileDeleteRetryInterval | Integer | 50 | How long in milliseconds to wait between attempting to delete a file |
| testProtocol | String | servlet | Arquillian protocol to contact the server to run a test (available: servlet or rest) |
| allowAppDeployFailures | boolean | false | When enabled, application deployment exceptions are not thrown. |


## Examples

### Example of Container Configuration (arquillian.xml)

```
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<arquillian xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
xmlns="http://jboss.org/schema/arquillian"
xsi:schemaLocation="http://jboss.org/schema/arquillian http://jboss.org/schema/arquillian/arquillian_1_0.xsd">

	<engine>
		<property name="deploymentExportPath">target/</property>
	</engine>
	
	<container qualifier="liberty-managed" default="true">
		<configuration>
			<property name="wlpHome">/opt/IBM/wlp/</property>
			<property name="serverName">defaultServer</property>
			<property name="httpPort">9080</property>
		</configuration>
	</container>
</arquillian>
```
