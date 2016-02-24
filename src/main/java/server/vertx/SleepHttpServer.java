package server.vertx;

import java.util.concurrent.TimeUnit;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpServer;

public class SleepHttpServer extends AbstractVerticle {
    
    private final int port;
    private HttpServer httpServer;
    
    public SleepHttpServer(final int port) {
        this.port = port;
    }
    
    @Override
    public void start() throws Exception {
        httpServer = vertx.createHttpServer();
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
    }
    
    @Override
    public void stop() throws Exception {
        if (httpServer != null) {
            httpServer.close();
        }
    }
}
