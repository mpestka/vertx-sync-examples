package server.vertx;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpServer;

public class SleepHttpServerAsync extends AbstractVerticle {

    private final int port;
    private HttpServer httpServer;
    
    public SleepHttpServerAsync(final int port) {
        this.port = port;
    }
    
    @Override
    public void start() throws Exception {
        httpServer = vertx.createHttpServer();
        httpServer.requestHandler(req -> {

            long tid = vertx.setTimer(1000, h -> {
                final String body = SleepHttpServerAsync.class.getSimpleName();
                req.response()
                    .putHeader("Content-Length", String.valueOf(body.length()))
                    .end(body);
            });

        }).listen(port);
    }
    
    @Override
    public void stop() throws Exception {
        if (httpServer != null) {
            httpServer.close();
        }
    }
}
