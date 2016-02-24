package server.vertx;

import java.util.concurrent.TimeUnit;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpServer;

public class BlockingHttpServer extends AbstractVerticle {

    private final int port;
    private HttpServer httpServer;
    
    public BlockingHttpServer(final int port) {
        this.port = port;
    }
    
    @Override
    public void start() throws Exception {
        httpServer = vertx.createHttpServer();
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
                false,
                result -> {
                    final String body = String.valueOf(result.result());
                    req.response()
                        .putHeader("Content-Length", String.valueOf(body.length()))
                        .end(body);
                }
            );

        }).listen(port);
    }

    @Override
    public void stop() throws Exception {
        if (httpServer != null) {
            httpServer.close();
        }
    }
}
