# Arquillian Liberty Server Containers [![Maven Central Latest](https://central.sonatype.com/artifact/io.openliberty.arquillian/arquillian-parent-liberty-jakarta)](https://central.sonatype.com/artifact/io.openliberty.arquillian/arquillian-parent-liberty-jakarta) [![Build Status](https://github.com/OpenLiberty/liberty-arquillian/actions/workflows/maven.yml/badge.svg?branch=main)](https://github.com/OpenLiberty/liberty-arquillian/actions?branch=main)

[Arquillian](http://arquillian.org/) is a testing framework to develop automated functional, integration and acceptance tests for your Java applications. Arquillian container adapters allow Arquillian to bind to and manage the lifecycle of a runtime. There are two types of Arquillian container adapters for Liberty: [Liberty Managed](#Arquillian-Liberty-Managed-Container) and [Liberty Remote](#Arquillian-Liberty-Remote-Container).

### Arquillian Liberty Managed Container

An Arquillian container adapter (`DeployableContainer` implementation) that can start and stop a local Liberty process and run tests on it over a remote protocol (effectively in a different JVM). For an introduction to testing microservices with the Arquillian Liberty Managed container and [Open Liberty](https://openliberty.io/), check out the [this guide](https://openliberty.io/guides/arquillian-managed.html).

**Jakarta EE 9, 10 and 11:** for Arquillian Liberty Managed container documentation with Jakarta EE 9, 10 and 11, click [here](liberty-managed/JakartaEE9_README.md).

**Java EE 8 or below:** for Arquillian Liberty Managed container documentation with Java EE 8 or below, click [here](liberty-managed/README.md).

### Arquillian Liberty Remote Container

An Arquillian container adapter (`DeployableContainer` implementation) that can connect and run against a remote (different JVM, different machine) Liberty server and run tests on it over a remote protocol (effectively in a different JVM).

**Jakarta EE 9, 10 and 11:** for Arquillian Liberty Remote container documentation with Jakarta EE 9, 10 and 11, click [here](liberty-remote/JakartaEE9_README.md).

**Java EE 8 or below:** for Arquillian Liberty Remote container documentation with Java EE 8 or below, click [here](liberty-remote/README.md).

### Testing

To run tests, you will need to specify the following parameters:

| Parameter        | Description |
| ---------------- | ----------- |
| runtime          | The runtime to use. Specify `ol` for Open Liberty, `olbeta` for Open Liberty beta, and `wlp-ee9`, `wlp-ee10` or `wlp-ee11` for WebSphere Liberty. |
| runtimeVersion   | Version of the specified runtime to use. |

For example, to run tests on version 26.0.0.3 of the Open Liberty runtime, use the following command:

```
mvn verify -Druntime=ol -DruntimeVersion=26.0.0.3
```

EE 9 and EE 10 archive images are no longer published for WebSphere Liberty. The runtimeVersion is ignored for both `wlp-ee9` and `wlp-ee10`. The last published version of the `wlp-jakartaee9` archive (23.0.0.2) and the `wlp-jakartaee10` archive (26.0.0.4) are used instead.
