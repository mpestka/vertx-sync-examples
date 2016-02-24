package server.vertx;



import java.util.concurrent.TimeUnit;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.Suspendable;
import co.paralleluniverse.strands.Strand;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.sync.Sync;
import io.vertx.ext.sync.SyncVerticle;


public class FiberHttpServer extends SyncVerticle {

    private final int port;
    private HttpServer httpServer;

    public FiberHttpServer(final int port) {
        this.port = port;
    }

    @Suspendable
    @Override
    public void start() throws Exception {
        httpServer = vertx.createHttpServer();
        httpServer.requestHandler(
            Sync.fiberHandler(req -> {

                try {
                    Strand.sleep(1, TimeUnit.SECONDS);                    // 1
                } catch (SuspendExecution | InterruptedException e) {     // 1
                    e.printStackTrace();                                  // 1
                }                                                         // 1

                //sleep();  // 2

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

    @Override
    public void stop() throws Exception {
        if (httpServer != null) {
            httpServer.close();
        }
    }
    
    

}
