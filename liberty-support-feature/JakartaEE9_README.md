# Arquillian support Liberty user feature with Jakarta EE 9, 10 and 11

A Liberty user feature which allows deployment exceptions to be reported more reliably when using the Liberty Managed Jakarta container.

The Arquillian support feature adds an additional http endpoint which the Arquillian container can query to determine the cause when an application fails to start.

It is only for supporting the running of Arquillian tests and must not be installed on a production system.

Requires Jakarta EE 9, 10 or 11. For Java EE 8 projects and below, check out the documentation [here](README.md).

## Configuring with a Maven project

You can install the arquillian-liberty-support-jakarta feature as part of a maven build using the [maven-dependency-plugin:unpack goal](https://maven.apache.org/plugins/maven-dependency-plugin/unpack-mojo.html).

Example:

```xml
<dependencies>
  <dependency>
    <groupId>io.openliberty.arquillian</groupId>
    <artifactId>arquillian-liberty-support-jakarta</artifactId>
    <version>3.0.0</version>
  </dependency>
</dependencies>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <version>3.1.1</version>
  <executions>
    <execution>
      <id>extract-support-feature</id>
      <phase>pre-integration-test</phase>
      <goals>
        <goal>unpack</goal>
      </goals>
    </execution>
  </executions>
  <configuration>
    <artifactItems>
      <artifactItem>
        <groupId>io.openliberty.arquillian</groupId>
        <artifactId>arquillian-liberty-support-jakarta</artifactId>
        <version>3.0.0</version>
        <type>zip</type>
        <classifier>feature</classifier>
        <overWrite>false</overWrite>
        <outputDirectory>${project.build.directory}/liberty/wlp/usr</outputDirectory>
      </artifactItem>
    </artifactItems>
  </configuration>
</plugin>
```
Then add `<feature>usr:arquillian-support-jakarta-3.0</feature>` to the `<featureManager>` section of your `server.xml`.
```
<featureManager>
    <feature>pages-3.0</feature>
    <feature>localConnector-1.0</feature>
    <feature>usr:arquillian-support-jakarta-3.0</feature>
</featureManager>
```

## Configuring manually

1. Generate the arquillian-liberty-support-jakarta-x.x.x-feature.zip by cloning and building this project. The zip will be generated into the `/liberty-arquillian/liberty-support-feature/target` directory.
```
git clone git@github.com:OpenLiberty/liberty-arquillian.git
mvn install
```
2.  Extract the arquillian-liberty-support-jakarta-x.x.x-feature.zip into the `usr` directory of your Liberty runtime
3. Add `<feature>usr:arquillian-support-jakarta-3.0</feature>` to the `<featureManager>` section of your `server.xml`

```
<featureManager>
    <feature>pages-3.0</feature>
    <feature>localConnector-1.0</feature>
    <feature>usr:arquillian-support-jakarta-3.0</feature>
</featureManager>
```