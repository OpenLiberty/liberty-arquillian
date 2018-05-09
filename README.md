# Arquillian Liberty Server Containers

For Arquillian Liberty Managed container documentation, click [here](https://github.com/OpenLiberty/liberty-arquillian/tree/master/liberty-managed/README.md).

For Arquillian Liberty Remote container documentation, click [here](https://github.com/OpenLiberty/liberty-arquillian/tree/master/liberty-remote/README.md).

### Testing

To run tests, you will need to specify the following parameters:

| Parameter        | Description |
| ---------------- | ----------- |
| runtime          | The runtime to use. Specify `ol` for Open Liberty and `wlp` for WebSphere Liberty. |
| runtimeVersion   | Version of the specified runtime to use. |

For example, to run tests on version 18.0.0.1 of the Open Liberty runtime, use the following command:

```
mvn verify -Druntime=ol -DruntimeVersion=18.0.0.1
```