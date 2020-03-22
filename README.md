# gamelift-sdk-java
Java port of the server and client-side Amazon GameLift SDK.

### Installation

```xml
<repositories>
    <repository>
        <id>ashcon-repo</id>
        <url>https://repo.ashcon.app/content/repositories/releases</url>
    </repository>
</repositories>
```

```xml
<dependencies>
    <dependency>
        <groupId>app.ashcon.gamelift</groupId>
        <artifactId>gamelift-sdk</artifactId>
        <version>3.4.0.3</version>
    </dependency>
</dependencies>
```

### Usage

API design is derivied from the [GameLift C# SDK](https://docs.aws.amazon.com/gamelift/latest/developerguide/gamelift-sdk-server-api.html), which is maintained by Amazon.

```java
// Client side: sends requests to AWS for game and player sessions
AmazonGameLiftClientBuilder.defaultClient().createGameSession( ... );

// Server side: responds to AWS events and actually hosts games
AmazonGameLiftServer.get().initSdk();
```