package pt.ulisboa.tecnico.cnv.master.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import pt.ulisboa.tecnico.cnv.master.state.WorkerNode;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.regions.Region;
import pt.ulisboa.tecnico.cnv.master.config.LoadBalancerConfig;
import pt.ulisboa.tecnico.cnv.common.config.AwsConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import java.time.Duration;

/**
 * Handles all the low-level HTTP plumbing.
 * Separates the routing logic (Proxy) from the actual network execution.
 */
public class HttpDispatcher {

    public void forwardToEc2(HttpExchange exchange, WorkerNode targetNode, byte[] requestBody) throws IOException {
        String targetUrl = buildTargetUrl(exchange, targetNode.getAddress());
        executeHttpRequest(exchange, targetUrl, requestBody, LoadBalancerConfig.getProxyConnectTimeout(), LoadBalancerConfig.getProxyReadTimeout());
    }

   public void forwardToLambda(HttpExchange exchange, byte[] requestBody) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path != null && path.startsWith("/")) {
        path = path.substring(1);
    }

    String query = exchange.getRequestURI().getRawQuery();

    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();
    root.put("operation", path == null ? "" : path);

    if (query != null && !query.isEmpty()) {
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }

            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length == 2
                    ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8)
                    : "";

            root.put(key, value);
        }
    }

    String payloadJson = mapper.writeValueAsString(root);
    SdkBytes payload = SdkBytes.fromUtf8String(payloadJson);

    try (LambdaClient awsLambda = LambdaClient.builder()
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .region(Region.of(AwsConfig.getRegion()))
            .httpClientBuilder(ApacheHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(10))
                    .socketTimeout(Duration.ofSeconds(180)))
            .overrideConfiguration(cfg -> cfg
                    .apiCallTimeout(Duration.ofSeconds(180))
                    .apiCallAttemptTimeout(Duration.ofSeconds(180)))
            .build()) {

        InvokeRequest request = InvokeRequest.builder()
                .functionName("NatureLambda")
                .payload(payload)
                .build();

        InvokeResponse res = awsLambda.invoke(request);
        String responseValue = res.payload() == null ? "" : res.payload().asUtf8String();

        int statusCode = (res.functionError() == null && res.statusCode() == 200) ? 200 : 502;
        byte[] responseBytes = responseValue.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }

    } catch (Exception e) {
        System.err.println("[ERROR] AWS Lambda SDK invocation failed: " + e.getMessage());
        byte[] responseBytes = ("{\"error\":\"Lambda invocation failed\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(502, responseBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}

    private void executeHttpRequest(HttpExchange exchange, String targetUrl, byte[] requestBody, int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
        conn.setRequestMethod(exchange.getRequestMethod());
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setDoInput(true);

        this.copyRequestHeaders(exchange, conn);

        if (requestBody.length > 0 && hasRequestBody(exchange.getRequestMethod())) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody);
            }
        } else {
            conn.connect();
        }

        int statusCode = conn.getResponseCode();

        InputStream responseStream = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (responseStream == null) {
            responseStream = InputStream.nullInputStream();
        }

        copyResponseHeaders(conn, exchange);

        byte[] responseBytes;
        try (InputStream is = responseStream) {
            responseBytes = is.readAllBytes();
        }

        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        } finally {
            conn.disconnect();
        }
    }

    public void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        exchange.sendResponseHeaders(code, msg.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(msg.getBytes());
        }
    }

    private String buildTargetUrl(HttpExchange exchange, String baseAddress) {
        StringBuilder url = new StringBuilder(baseAddress);

        String path = exchange.getRequestURI().getPath();
        if (path != null && !path.isEmpty()) {
            if (url.length() > 0 && url.charAt(url.length() - 1) == '/' && path.startsWith("/")) {
                url.setLength(url.length() - 1);
            }
            url.append(path);
        }

        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && !query.isBlank()) {
            url.append('?').append(query);
        }

        return url.toString();
    }

    private boolean hasRequestBody(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
    }

    private void copyRequestHeaders(HttpExchange exchange, HttpURLConnection conn) {
        Headers headers = exchange.getRequestHeaders();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null) continue;

            if (name.equalsIgnoreCase("Host")
                    || name.equalsIgnoreCase("Connection")
                    || name.equalsIgnoreCase("Content-Length")
                    || name.equalsIgnoreCase("Transfer-Encoding")) {
                continue;
            }

            for (String value : entry.getValue()) {
                conn.addRequestProperty(name, value);
            }
        }
    }

    private void copyResponseHeaders(HttpURLConnection conn, HttpExchange exchange) {
        Headers responseHeaders = exchange.getResponseHeaders();
        for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
            String name = entry.getKey();
            if (name == null) continue;

            if (name.equalsIgnoreCase("Transfer-Encoding")
                    || name.equalsIgnoreCase("Content-Length")
                    || name.equalsIgnoreCase("Connection")) {
                continue;
            }

            responseHeaders.put(name, entry.getValue());
        }
    }
}