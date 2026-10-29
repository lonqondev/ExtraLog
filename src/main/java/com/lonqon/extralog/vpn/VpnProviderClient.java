package com.lonqon.extralog.vpn;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class VpnProviderClient {
    private final HttpClient httpClient;

    public VpnProviderClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public CompletableFuture<VpnProviderResult> lookup(VpnProvider provider, String apiKey, String ip, int timeoutMillis) {
        HttpRequest request;
        try {
            request = buildRequest(provider, apiKey, ip, timeoutMillis);
        } catch (Exception exception) {
            return failed(new VpnProviderException("VPN isteği hazırlanamadı.", exception));
        }

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> parseResponse(provider, response));
    }

    private HttpRequest buildRequest(VpnProvider provider, String apiKey, String ip, int timeoutMillis) {
        String encodedIp = encode(ip);
        String encodedKey = encode(apiKey);
        URI uri;
        HttpRequest.Builder builder;
        switch (provider) {
            case PROXYCHECK -> {
                String keyPart = apiKey.isBlank() ? "" : "&key=" + encodedKey;
                uri = URI.create("https://proxycheck.io/v2/" + encodedIp + "?vpn=1&risk=1&asn=1" + keyPart);
                builder = HttpRequest.newBuilder(uri);
            }
            case IPHUB -> {
                uri = URI.create("https://v2.api.iphub.info/ip/" + encodedIp);
                builder = HttpRequest.newBuilder(uri).header("X-Key", apiKey).header("Accept-Version", "2.2");
            }
            case IPQUALITYSCORE -> {
                uri = URI.create("https://www.ipqualityscore.com/api/json/ip/" + encodedKey + "/" + encodedIp + "?strictness=0");
                builder = HttpRequest.newBuilder(uri);
            }
            default -> throw new IllegalArgumentException("Desteklenmeyen VPN provider.");
        }
        return builder.timeout(Duration.ofMillis(timeoutMillis)).header("Accept", "application/json").GET().build();
    }

    private CompletableFuture<VpnProviderResult> parseResponse(VpnProvider provider, HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 429) {
            return failed(new VpnProviderException("VPN provider rate limit uyguladı."));
        }
        if (status < 200 || status >= 300) {
            return failed(new VpnProviderException("VPN provider HTTP " + status + " döndürdü."));
        }
        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            return CompletableFuture.completedFuture(parse(provider, root));
        } catch (Exception exception) {
            return failed(new VpnProviderException("VPN provider yanıtı geçersiz.", exception));
        }
    }

    private VpnProviderResult parse(VpnProvider provider, JsonObject root) {
        return switch (provider) {
            case PROXYCHECK -> parseProxyCheck(root);
            case IPHUB -> parseIpHub(root);
            case IPQUALITYSCORE -> parseIpQualityScore(root);
        };
    }

    private VpnProviderResult parseProxyCheck(JsonObject root) {
        String status = string(root, "status");
        if (status != null && !status.equalsIgnoreCase("ok")) {
            throw new VpnProviderException("ProxyCheck yanıtı başarısız.");
        }
        JsonObject ipObject = null;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                ipObject = entry.getValue().getAsJsonObject();
                break;
            }
        }
        if (ipObject == null) {
            throw new VpnProviderException("ProxyCheck IP sonucu bulunamadı.");
        }
        String type = string(ipObject, "type");
        boolean proxy = booleanValue(ipObject, "proxy") || containsDetection(type);
        return new VpnProviderResult(
                proxy,
                firstNonBlank(string(ipObject, "provider"), string(ipObject, "asn"), "ProxyCheck"),
                firstNonBlank(string(ipObject, "country"), string(ipObject, "countrycode"), "unknown"),
                firstNonBlank(string(ipObject, "risk"), type, "unknown")
        );
    }

    private VpnProviderResult parseIpHub(JsonObject root) {
        if (root.has("error")) {
            throw new VpnProviderException("IPHub yanıtı başarısız.");
        }
        int block = intValue(root, "block", -1);
        if (block < 0) {
            throw new VpnProviderException("IPHub block sonucu bulunamadı.");
        }
        return new VpnProviderResult(
                block >= 1,
                firstNonBlank(string(root, "isp"), "IPHub"),
                firstNonBlank(string(root, "countryName"), string(root, "countryCode"), "unknown"),
                block + firstNonBlank(string(root, "blockReason"), "")
        );
    }

    private VpnProviderResult parseIpQualityScore(JsonObject root) {
        if (!booleanValue(root, "success")) {
            throw new VpnProviderException("IPQualityScore yanıtı başarısız.");
        }
        boolean proxy = booleanValue(root, "proxy") || booleanValue(root, "vpn") || booleanValue(root, "tor");
        return new VpnProviderResult(
                proxy,
                firstNonBlank(string(root, "ISP"), string(root, "Organization"), "IPQualityScore"),
                firstNonBlank(string(root, "country_code"), "unknown"),
                firstNonBlank(string(root, "fraud_score"), "unknown")
        );
    }

    private boolean containsDetection(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase();
        return normalized.contains("vpn") || normalized.contains("proxy") || normalized.contains("tor");
    }

    private boolean booleanValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return false;
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        return value.getAsString().equalsIgnoreCase("yes") || value.getAsString().equalsIgnoreCase("true");
    }

    private int intValue(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private String firstNonBlank(String first, String fallback) {
        return firstNonBlank(first, null, fallback);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private <T> CompletableFuture<T> failed(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    public static final class VpnProviderException extends RuntimeException {
        public VpnProviderException(String message) {
            super(message);
        }

        public VpnProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
