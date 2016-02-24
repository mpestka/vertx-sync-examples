package server.vertx;

import io.vertx.core.AbstractVerticle;

public class VertxLauncher extends AbstractVerticle {

    @Override
    public void start() throws Exception {
        vertx.deployVerticle(new SleepHttpServerAsync(8000));
        vertx.deployVerticle(new SleepHttpServer(8001));
        vertx.deployVerticle(new BlockingHttpServer(8002));
        vertx.deployVerticle(new FiberHttpServer(8003));
    }
}
