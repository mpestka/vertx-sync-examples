# vertx-sync-examples
Marriage of Vertx and Quasar Fibers from http://www.paralleluniverse.co/quasar/


This repository contains a few simple examples how we can implement HTTP servlet.


Few days ago I've seen a presentation about Vertx, which compared it to Tomcat using some micro-benchmarks for simple ping-pong HTTP requests.


As it compared asynchronous vertx approach with synchronous thread-blocking code using tomcat, I felt it's a bit unfair (as often we cannot avoid using synchronous API ex. JDBC) and thus I created a few examples how we could do more 'fair' comparison...


All examples are in simple maven project that contains a few verticles, each of them implements simple HTTP server.
For micro-benchmarking wrk is used.

###Building the project
For building the project please use aot profile, as it enables Fibers by instruments bytecode during compilation.
It user quasar-maven-plugin that generates some 'Class not found' messages which can be ignored.
mvn -Paot package

##1. Original non-blocking example from presentation
Well, actually I do not have original source code, but it was something like SleepHttpServerAsync.java:

```java
        httpServer.requestHandler(req -> {
            long tid = vertx.setTimer(1000, h -> {
                final String body = SleepHttpServerAsync.class.getSimpleName();
                req.response()
                    .putHeader("Content-Length", String.valueOf(body.length()))
                    .end(body);
            });
        }).listen(port);
```

This code actually does not block as timer only delays response in asynchronous way. 
Let's start the server:

```
[root@5f1832619cc7 mnt]# cd vertx-sync-examples/target/
[root@5f1832619cc7 target]# java -jar serverVertx-0.0.1-SNAPSHOT-fat.jar &
[1] 5231
[root@5f1832619cc7 target]# Feb 24, 2016 10:26:44 PM io.vertx.core.impl.launcher.commands.VertxIsolatedDeployer
INFO: Succeeded in deploying verticle
QUASAR WARNING: Quasar Java Agent isn't running. If you're using another instrumentation method you can ignore this message; otherwise, please refer to the Getting Started section in the Quasar documentation.
```
We can ignore quasar warning, as we already instrumented the code in maven profile...
Now let's test it with curl:
```
[root@5f1832619cc7 target]# curl localhost:8000
SleepHttpServerAsync[root@5f1832619cc7 target]#
```
OK, we have a response after about one second delay.

Here is the micro-benchmark result with 100 parallel connections for 10 seconds (actually I run every benchmark twice and record only second run just in case JVM needs to warm-up):
```
[root@5f1832619cc7 target]# curl localhost:8000
SleepHttpServerAsync[root@5f1832619cc7 target]# wrk -t100 -c100 -d10 http://localhost:8000
Running 10s test @ http://localhost:8000
  100 threads and 100 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.00s     2.94ms   1.01s    76.96%
    Req/Sec     0.02      0.15     1.00     97.66%
  942 requests in 10.04s, 54.28KB read
Requests/sec:     93.81
Transfer/sec:      5.41KB
```
We were able to achieve almost 94 requests per second :)

##2. Blocking code using vertx - do not do it :)
Now let's try the blocking code (here I will use vertx too, just for comparison from SleepHttpServer.java)
```java
        httpServer.requestHandler(req -> {
            try {
                // CAUTION: Do NOT call blocking code in vertx event loop (this is a clear anti-pattern):
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.interrupted();
            }
            final String body = SleepHttpServer.class.getSimpleName();
            req.response()
                .putHeader("Content-Length", String.valueOf(body.length()))
                .end(body);
        }).listen(port);
```
and the wrk result:
```
[root@5f1832619cc7 target]# curl localhost:8001
SleepHttpServer[root@5f1832619cc7 target]# wrk -t100 -c100 -d10 http://localhost:8001
Running 10s test @ http://localhost:8001
  100 threads and 100 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.01s    18.10ms   1.03s   100.00%
    Req/Sec     0.00      0.00     0.00    100.00%
  10 requests in 10.04s, 540.00B read
  Socket errors: connect 0, read 0, write 0, timeout 8
Requests/sec:      1.00
Transfer/sec:      53.78B
```
OK, that was just for illustration what will happen if you block event-loop thread: we have just single request per second.
This is because we created just one server verticle listening on port 8001, and it's thread has been blocked.
That would actually reflect situation when we use tomcat with single thread for handling requests...

##3. Worker threads for blocking operations
Assuming we are forced to use blocking API with long running operations, vertex advises documentation using worker threads. It has even special API so we do not have to create them manually (code from BlockingHttpServer.java):
```java
        httpServer.requestHandler(req -> {
            vertx.executeBlocking(
                future -> {
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.interrupted();
                    }    
                    future.complete(BlockingHttpServer.class.getSimpleName());
                },
                false, // parallel invocation please
                result -> {
                    final String body = String.valueOf(result.result());
                    req.response()
                        .putHeader("Content-Length", String.valueOf(body.length()))
                        .end(body);
                }
            );
        }).listen(port);
```
As we can see we just use 'executeBlocking' to tell vertx to execute our code in worker thread - no need to create it manually (Although to tell the truth I do not like personally this API especially instead of second boolean parameter we could have 2 versions of blocking function one for ordered and second for parallel execution).
Lets see the performance:
```
[root@5f1832619cc7 target]# curl localhost:8002
BlockingHttpServer[root@5f1832619cc7 target]# wrk -t100 -c100 -d10 http://localhost:8002
Running 10s test @ http://localhost:8002
  100 threads and 100 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.50s   503.01ms   2.00s   100.00%
    Req/Sec     0.00      0.00     0.00    100.00%
  200 requests in 10.06s, 11.13KB read
  Socket errors: connect 0, read 0, write 0, timeout 160
Requests/sec:     19.89
Transfer/sec:      1.11KB
```
Well, almost 20 requests per second. I did not touch vertx executor service thread pool size, so probably by default it uses just 20 worker threads It can be tuned in vertex configuration, but as we can see it looks like standard synchronous approach where we have thread-per-request, and the thread is blocked by our synchronous long-running task. 
Now we could compare this approach to the tomcat mentioned in the beginning - at least for synchronous API.

##4. Vertx-sync - Fibers!
There is another way of calling sync API in vertx - using Fibers. Actually this approach uses quasar library - which is totally independent from vertx, but lets stick with vertx here as it has integration for fibers called vertx-sync (http://vertx.io/docs/vertx-sync/java/):

```java
    @Suspendable
    @Override
    public void start() throws Exception {
        httpServer = vertx.createHttpServer();
        httpServer.requestHandler(
            Sync.fiberHandler(req -> {
                sleep();  // blocking method
                final String body = FiberHttpServer.class.getName();
                req.response()
                    .putHeader("Content-Length", String.valueOf(body.length()))
                    .end(body);
        })).listen(port);
    }
    @Suspendable
    public void sleep() {
        try {
            Strand.sleep(1, TimeUnit.SECONDS);
        } catch (SuspendExecution | InterruptedException e) {
            e.printStackTrace();
        }
    }
        
```
wrk micro-benchmark:
```
[root@5f1832619cc7 target]# curl localhost:8003
server.vertx.FiberHttpServer[root@5f1832619cc7 target]# wrk -t100 -c100 -d10 http://localhost:8003
Running 10s test @ http://localhost:8003
  100 threads and 100 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.01s     4.86ms   1.03s    75.35%
    Req/Sec     0.02      0.13     1.00     98.17%
  929 requests in 10.09s, 60.78KB read
Requests/sec:     92.03
Transfer/sec:      6.02KB
```
 ... and we are back at over 90 requests per second, this time using blocking API!
 
 
 
 
 
 ### Issues with Vertx-sync
 Please notice that in last example I had to move sleep to separate method as the following code does not work:
 
 ```java
         httpServer.requestHandler(
            Sync.fiberHandler(req -> {
                try {
                    Strand.sleep(1, TimeUnit.SECONDS);                    // 1
                } catch (SuspendExecution | InterruptedException e) {     // 1
                    e.printStackTrace();                                  // 1
                }                                                         // 1
                final String body = FiberHttpServer.class.getName();
                req.response()
                    .putHeader("Content-Length", String.valueOf(body.length()))
                    .end(body);
        })).listen(port);
```

After running this code I get the following result:
```
[root@5f1832619cc7 target]# curl localhost:8003
co.paralleluniverse.fibers.SuspendExecution: Oops. Forgot to instrument a method. Run your program with -Dco.paralleluniverse.fibers.verifyInstrumentation=true to catch the culprit!
```
I got the response but immiediatelly (without 1 second delay) plus helpful quasar hint. This probably means that we got an exception during sleep - let's follow quasar advice and run the server again with suggested verification parameter... The result is not very helpful:

```
[root@5f1832619cc7 target]# java -jar serverVertx-0.0.1-SNAPSHOT-fat.jar -Dco.paralleluniverse.fibers.verifyInstrumentation=true &
[1] 6470
[root@5f1832619cc7 target]# Feb 24, 2016 11:45:31 PM io.vertx.core.impl.launcher.commands.VertxIsolatedDeployer
INFO: Succeeded in deploying verticle
QUASAR WARNING: Quasar Java Agent isn't running. If you're using another instrumentation method you can ignore this message; otherwise, please refer to the Getting Started section in the Quasar documentation.
[root@5f1832619cc7 target]# curl localhost:8003
Exception in Fiber "fiber-vertx.contextScheduler-10000002" java.lang.IllegalArgumentException: Class name mismatch: io.vertx.ext.sync.Sync, server.vertx.FiberHttpServer$$Lambda$33/364440437
        at co.paralleluniverse.common.util.ExtendedStackTraceElement.<init>(ExtendedStackTraceElement.java:50)
        at co.paralleluniverse.common.util.ExtendedStackTraceElement.<init>(ExtendedStackTraceElement.java:39)
        at co.paralleluniverse.common.util.ExtendedStackTrace$BasicExtendedStackTraceElement.<init>(ExtendedStackTrace.java:178)
        at co.paralleluniverse.common.util.ExtendedStackTraceClassContext.get(ExtendedStackTraceClassContext.java:54)
        at co.paralleluniverse.fibers.Fiber.checkInstrumentation(Fiber.java:1626)
        at co.paralleluniverse.fibers.Fiber.checkInstrumentation(Fiber.java:1618)
        at co.paralleluniverse.fibers.Fiber.verifySuspend(Fiber.java:1591)
        at co.paralleluniverse.fibers.Fiber.verifySuspend(Fiber.java:1586)
        at co.paralleluniverse.fibers.Fiber.sleep(Fiber.java:635)
        at co.paralleluniverse.strands.Strand.sleep(Strand.java:425)
        at server.vertx.FiberHttpServer.lambda$0(FiberHttpServer.java:32)
        at io.vertx.ext.sync.Sync.lambda$null$19031fba$1(Sync.java:148)
        at co.paralleluniverse.strands.SuspendableUtils$VoidSuspendableCallable.run(SuspendableUtils.java:44)
        at co.paralleluniverse.strands.SuspendableUtils$VoidSuspendableCallable.run(SuspendableUtils.java:32)
        at co.paralleluniverse.fibers.Fiber.run(Fiber.java:1024)
WARNING: Uninstrumented methods (marked '**') or call-sites (marked '!!') detected on the call stack:
        at co.paralleluniverse.common.util.ExtendedStackTraceElement.co.paralleluniverse.common.util.ExtendedStackTraceElement(java.lang.String,java.lang.String,java.lang.String,int,java.lang.Class,java.lang.reflect.Member,int) (ExtendedStackTraceElement.java:50) **
        at co.paralleluniverse.common.util.ExtendedStackTraceElement.<init> (ExtendedStackTraceElement.java:39) **
        at co.paralleluniverse.common.util.ExtendedStackTrace$BasicExtendedStackTraceElement.<init> (ExtendedStackTrace.java:178) **
        at co.paralleluniverse.common.util.ExtendedStackTraceClassContext.get (ExtendedStackTraceClassContext.java:54) **
        at co.paralleluniverse.fibers.Fiber.checkInstrumentation (Fiber.java:1626)
        at co.paralleluniverse.fibers.Fiber.checkInstrumentation (Fiber.java:1618)
        at co.paralleluniverse.fibers.Fiber.verifySuspend (Fiber.java:1591)
        at co.paralleluniverse.fibers.Fiber.verifySuspend (Fiber.java:1586)
        at co.paralleluniverse.fibers.Fiber.sleep (Fiber.java:635)
        at co.paralleluniverse.strands.Strand.sleep (Strand.java:425)
        at server.vertx.FiberHttpServer.lambda$0 (FiberHttpServer.java:32) **
        at io.vertx.ext.sync.Sync.lambda$null$19031fba$1 (Sync.java:148) (optimized)
        at co.paralleluniverse.strands.SuspendableUtils$VoidSuspendableCallable.run (SuspendableUtils.java:44)
        at co.paralleluniverse.strands.SuspendableUtils$VoidSuspendableCallable.run (SuspendableUtils.java:32)
        at co.paralleluniverse.fibers.Fiber.run (Fiber.java:1024)
        at co.paralleluniverse.fibers.Fiber.run1 (Fiber.java:1019)
Exception in Fiber "fiber-vertx.contextScheduler-10000002" java.lang.IllegalArgumentException: Class name mismatch: io.vertx.ext.sync.Sync, server.vertx.FiberHttpServer$$Lambda$33/364440437
        at co.paralleluniverse.common.util.ExtendedStackTraceElement.<init>(ExtendedStackTraceElement.java:50)
        at co.paralleluniverse.common.util.ExtendedStackTraceElement.<init>(ExtendedStackTraceElement.java:39)
        at co.paralleluniverse.common.util.ExtendedStackTrace$BasicExtendedStackTraceElement.<init>(ExtendedStackTrace.java:178)
        at co.paralleluniverse.common.util.ExtendedStackTraceClassContext.get(ExtendedStackTraceClassContext.java:54)
        at co.paralleluniverse.fibers.Fiber.checkInstrumentation(Fiber.java:1626)
        at co.paralleluniverse.fibers.Fiber.checkInstrumentation(Fiber.java:1618)
        at co.paralleluniverse.fibers.Fiber.verifySuspend(Fiber.java:1591)
        at co.paralleluniverse.fibers.Fiber.verifySuspend(Fiber.java:1586)
        at co.paralleluniverse.fibers.Fiber.sleep(Fiber.java:635)
        at co.paralleluniverse.strands.Strand.sleep(Strand.java:425)
        at server.vertx.FiberHttpServer.lambda$0(FiberHttpServer.java:32)
        at io.vertx.ext.sync.Sync.lambda$null$19031fba$1(Sync.java:148)
        at co.paralleluniverse.strands.SuspendableUtils$VoidSuspendableCallable.run(SuspendableUtils.java:44)
        at co.paralleluniverse.strands.SuspendableUtils$VoidSuspendableCallable.run(SuspendableUtils.java:32)
        at co.paralleluniverse.fibers.Fiber.run(Fiber.java:1024)
WARNING: Uninstrumented methods (marked '**') or call-sites (marked '!!') detected on the call stack:
        at co.paralleluniverse.common.util.ExtendedStackTraceElement.co.paralleluniverse.common.util.ExtendedStackTraceElement(java.lang.String,java.lang.String,java.lang.String,int,java.lang.Class,java.lang.reflect.Member,int) (ExtendedStackTraceElement.java:50) **
        at co.paralleluniverse.common.util.ExtendedStackTraceElement.<init> (ExtendedStackTraceElement.java:39) **
        at co.paralleluniverse.common.util.ExtendedStackTrace$BasicExtendedStackTraceElement.<init> (ExtendedStackTrace.java:178) **
        at co.paralleluniverse.common.util.ExtendedStackTraceClassContext.get (ExtendedStackTraceClassContext.java:54) **
        at co.paralleluniverse.fibers.Fiber.checkInstrumentation (Fiber.java:1626)
        at co.paralleluniverse.fibers.Fiber.checkInstrumentation (Fiber.java:1618)
        at co.paralleluniverse.fibers.Fiber.verifySuspend (Fiber.java:1591)
        at co.paralleluniverse.fibers.Fiber.verifySuspend (Fiber.java:1586)
        at co.paralleluniverse.fibers.Fiber.sleep (Fiber.java:635)
        at co.paralleluniverse.strands.Strand.sleep (Strand.java:425)
        at server.vertx.FiberHttpServer.lambda$0 (FiberHttpServer.java:32) **
        at io.vertx.ext.sync.Sync.lambda$null$19031fba$1 (Sync.java:148) (optimized)
        at co.paralleluniverse.strands.SuspendableUtils$VoidSuspendableCallable.run (SuspendableUtils.java:44)
        at co.paralleluniverse.strands.SuspendableUtils$VoidSuspendableCallable.run (SuspendableUtils.java:32)
        at co.paralleluniverse.fibers.Fiber.run (Fiber.java:1024)
        at co.paralleluniverse.fibers.Fiber.run1 (Fiber.java:1019)
Feb 24, 2016 11:45:44 PM io.vertx.core.impl.ContextImpl
SEVERE: Unhandled exception
java.lang.IllegalArgumentException: Class name mismatch: io.vertx.ext.sync.Sync, server.vertx.FiberHttpServer$$Lambda$33/364440437
        at co.paralleluniverse.common.util.ExtendedStackTraceElement.<init>(ExtendedStackTraceElement.java:50)
        at co.paralleluniverse.common.util.ExtendedStackTraceElement.<init>(ExtendedStackTraceElement.java:39)
        at co.paralleluniverse.common.util.ExtendedStackTrace$BasicExtendedStackTraceElement.<init>(ExtendedStackTrace.java:178)
        at co.paralleluniverse.common.util.ExtendedStackTraceClassContext.get(ExtendedStackTraceClassContext.java:54)
        at co.paralleluniverse.fibers.Fiber.checkInstrumentation(Fiber.java:1626)
        at co.paralleluniverse.fibers.Fiber.checkInstrumentation(Fiber.java:1618)
        at co.paralleluniverse.fibers.Fiber.verifySuspend(Fiber.java:1591)
        at co.paralleluniverse.fibers.Fiber.verifySuspend(Fiber.java:1586)
        at co.paralleluniverse.fibers.Fiber.sleep(Fiber.java:635)
        at co.paralleluniverse.strands.Strand.sleep(Strand.java:425)
        at server.vertx.FiberHttpServer.lambda$0(FiberHttpServer.java:32)
        at io.vertx.ext.sync.Sync.lambda$null$19031fba$1(Sync.java:148)
        at co.paralleluniverse.strands.SuspendableUtils$VoidSuspendableCallable.run(SuspendableUtils.java:44)
        at co.paralleluniverse.strands.SuspendableUtils$VoidSuspendableCallable.run(SuspendableUtils.java:32)
        at co.paralleluniverse.fibers.Fiber.run(Fiber.java:1024)
        at co.paralleluniverse.fibers.Fiber.run1(Fiber.java:1019)
        at co.paralleluniverse.fibers.Fiber.exec(Fiber.java:730)
        at co.paralleluniverse.fibers.RunnableFiberTask.doExec(RunnableFiberTask.java:94)
        at co.paralleluniverse.fibers.RunnableFiberTask.run(RunnableFiberTask.java:85)
        at io.vertx.ext.sync.Sync.lambda$getContextScheduler$2(Sync.java:198)
        at co.paralleluniverse.fibers.FiberExecutorScheduler.execute(FiberExecutorScheduler.java:77)
        at co.paralleluniverse.fibers.RunnableFiberTask.submit(RunnableFiberTask.java:265)
        at co.paralleluniverse.fibers.Fiber.start(Fiber.java:1055)
        at io.vertx.ext.sync.Sync.lambda$fiberHandler$0(Sync.java:148)
        at io.vertx.core.http.impl.ServerConnection.handleRequest(ServerConnection.java:274)
        at io.vertx.core.http.impl.ServerConnection.processMessage(ServerConnection.java:392)
        at io.vertx.core.http.impl.ServerConnection.handleMessage(ServerConnection.java:134)
        at io.vertx.core.http.impl.HttpServerImpl$ServerHandler.lambda$createConnAndHandle$27(HttpServerImpl.java:538)
        at io.vertx.core.impl.ContextImpl.lambda$wrapTask$18(ContextImpl.java:333)
        at io.vertx.core.impl.ContextImpl.executeFromIO(ContextImpl.java:225)
        at io.vertx.core.http.impl.HttpServerImpl$ServerHandler.createConnAndHandle(HttpServerImpl.java:536)
        at io.vertx.core.http.impl.HttpServerImpl$ServerHandler.doMessageReceived(HttpServerImpl.java:470)
        at io.vertx.core.http.impl.HttpServerImpl$ServerHandler.doMessageReceived(HttpServerImpl.java:421)
        at io.vertx.core.http.impl.VertxHttpHandler.channelRead(VertxHttpHandler.java:85)
        at io.vertx.core.net.impl.VertxHandler.channelRead(VertxHandler.java:124)
        at io.netty.channel.AbstractChannelHandlerContext.invokeChannelRead(AbstractChannelHandlerContext.java:318)
        at io.netty.channel.AbstractChannelHandlerContext.fireChannelRead(AbstractChannelHandlerContext.java:304)
        at io.netty.handler.codec.ByteToMessageDecoder.fireChannelRead(ByteToMessageDecoder.java:276)
        at io.netty.handler.codec.ByteToMessageDecoder.channelRead(ByteToMessageDecoder.java:263)
        at io.netty.channel.AbstractChannelHandlerContext.invokeChannelRead(AbstractChannelHandlerContext.java:318)
        at io.netty.channel.AbstractChannelHandlerContext.fireChannelRead(AbstractChannelHandlerContext.java:304)
        at io.netty.channel.DefaultChannelPipeline.fireChannelRead(DefaultChannelPipeline.java:846)
        at io.netty.channel.nio.AbstractNioByteChannel$NioByteUnsafe.read(AbstractNioByteChannel.java:131)
        at io.netty.channel.nio.NioEventLoop.processSelectedKey(NioEventLoop.java:511)
        at io.netty.channel.nio.NioEventLoop.processSelectedKeysOptimized(NioEventLoop.java:468)
        at io.netty.channel.nio.NioEventLoop.processSelectedKeys(NioEventLoop.java:382)
        at io.netty.channel.nio.NioEventLoop.run(NioEventLoop.java:354)
        at io.netty.util.concurrent.SingleThreadEventExecutor$2.run(SingleThreadEventExecutor.java:112)
        at java.lang.Thread.run(Thread.java:745)
```




 
 