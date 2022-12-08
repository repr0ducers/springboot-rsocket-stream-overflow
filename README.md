## Spring RSocket/RSocket-java: server request-stream memory overflow 

### DESCRIPTION

Most recent release of RSocket/RSocket-java [1.1.3](https://github.com/rsocket/rsocket-java/releases/tag/1.1.3) integration 
from spring-boot ([3.0.0](https://docs.spring.io/spring-framework/docs/current/reference/html/rsocket.html) and 2.7.6) 
is affected by denial-of-service with stream memory overflow.

RSocket as protocol relies on Reactive Streams for message flow control — for streaming interactions (request-stream in particular), 
responder is not allowed to push more messages than requested by peer with REQUEST_N frame.

However, RSocket/RSocket-java does not enforce flow control - endpoints are allowed to produce more than their peers agreed to consume.

Malicious client may stop reading from socket at arbitrary moment of connection, start new stream, then send demand with 
REQUEST_N frame at arbitrarily high frequency. 

RSocket/RSocket-java allows arbitrary number of inbound REQUEST_N, does not limit outbound messages queue size, 
so host memory eventually gets overflown. Particularly, in case of infinite streams 1 stream is sufficient to cause 
memory overflow.

The difference from [rsocket-channel-overflow](https://github.com/repr0ducers/springboot-rsocket-channel-overflow) is
that responses stream is retained by server (responder) indefinitely, while with `channel` case server is overflown 
with request messages It did not asked for with REQUEST_N. 

### PREREQUISITES

jdk 8+

### SETUP

Spring-boot based application having RSocket-java service, started with 1GB memory limit: -Xms1024m, -Xmx1024m 
(`springboot-rsocket-service` module).

RSocket-java service implements request-stream interaction by echoing messages with request payload in response stream 
for demand requested by client. 

Malicious RSocket client (small subset sufficient for vulnerability demonstration) is implemented with Netty 
(`stream-overflow-client` module).

It establishes RSocket connection, sends single request-stream with payload of 1500 bytes to server, sends demand of 
42 messages with separate REQUEST_N frame 1000 times every 100 millis 
(total demand 42 * 1000 * 10 = 420_000 messages per second), but does not read any data from socket. 

### RUNNING

Build server, client binaries `./gradlew clean build installDist`

Run server `./springboot_rsocket_service.sh` 

Run client `./overflow_client.sh` 

Eventually (several seconds on a modern host, jdk11) `springboot-rsocket-service` reports `OutOfMemoryError`:

```
reactor.netty.ReactorNetty$InternalNettyException: java.lang.OutOfMemoryError: Direct buffer memory
Caused by: java.lang.OutOfMemoryError: Direct buffer memory
        at java.base/java.nio.Bits.reserveMemory(Bits.java:175)
        at java.base/java.nio.DirectByteBuffer.<init>(DirectByteBuffer.java:118)
        at java.base/java.nio.ByteBuffer.allocateDirect(ByteBuffer.java:317)
        at io.netty.buffer.PoolArena$DirectArena.allocateDirect(PoolArena.java:649)
        at io.netty.buffer.PoolArena$DirectArena.newChunk(PoolArena.java:624)
        at io.netty.buffer.PoolArena.allocateNormal(PoolArena.java:203)
        at io.netty.buffer.PoolArena.tcacheAllocateNormal(PoolArena.java:187)
        at io.netty.buffer.PoolArena.allocate(PoolArena.java:136)
        at io.netty.buffer.PoolArena.allocate(PoolArena.java:126)
        at io.netty.buffer.PooledByteBufAllocator.newDirectBuffer(PooledByteBufAllocator.java:396)
        at io.netty.buffer.AbstractByteBufAllocator.directBuffer(AbstractByteBufAllocator.java:188)
        at io.netty.buffer.AbstractByteBufAllocator.directBuffer(AbstractByteBufAllocator.java:179)
        at io.netty.channel.unix.PreferredDirectByteBufAllocator.ioBuffer(PreferredDirectByteBufAllocator.java:53)
        at io.netty.channel.DefaultMaxMessagesRecvByteBufAllocator$MaxMessageHandle.allocate(DefaultMaxMessagesRecvByteBufAllocator.java:120)
        at io.netty.channel.epoll.EpollRecvByteAllocatorHandle.allocate(EpollRecvByteAllocatorHandle.java:75)
        at io.netty.channel.epoll.AbstractEpollStreamChannel$EpollStreamUnsafe.epollInReady(AbstractEpollStreamChannel.java:785)
        at io.netty.channel.epoll.EpollEventLoop.processReady(EpollEventLoop.java:487)
        at io.netty.channel.epoll.EpollEventLoop.run(EpollEventLoop.java:385)
        at io.netty.util.concurrent.SingleThreadEventExecutor$4.run(SingleThreadEventExecutor.java:997)
        at io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74)
        at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
        at java.base/java.lang.Thread.run(Thread.java:829)

```