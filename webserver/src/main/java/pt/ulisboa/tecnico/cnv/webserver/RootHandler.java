package pt.ulisboa.tecnico.cnv.webserver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class RootHandler implements HttpHandler {

    private final String HELLO_MSG = "Default Web Server\n";

    @Override
    public void handle(HttpExchange he) throws IOException {
        he.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

        if (he.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            he.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            he.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            he.sendResponseHeaders(204, -1);
            he.close();
            return;
        }

        URI requestedUri = he.getRequestURI();
        String query = requestedUri.getRawQuery();

        String response;

        if (query == null) {
            response = HELLO_MSG;
        } else {
            response = "Query: " + query + "\n";
        }

        System.out.println("[RootHandler] Path: " + requestedUri.getPath());
        System.out.println("[RootHandler] Query: " + query);

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

        he.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        he.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = he.getResponseBody()) {
            os.write(bytes);
        }
    }
}
