package fr.openobject.ai.syleks.provider;

import java.io.IOException;
import java.util.Map;

/** Diagnostics contain only bounded, redacted provider data, never request headers or bodies. */
public class ProviderException extends IOException {
    private final int status;
    private final String code;

    public ProviderException(int status, String code, String message, String requestId) {
        super(
                "HTTP "
                        + status
                        + " · "
                        + safe(code)
                        + " · "
                        + safe(message)
                        + (requestId == null ? "" : " · request " + safe(requestId)));
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ProviderException from(
            int status, Map<?, ?> body, String requestId, String... secrets) {
        Object error = body.get("error");
        Map<?, ?> detail = error instanceof Map<?, ?> map ? map : body;
        String code = String.valueOf(detail.getOrDefault("code", null));
        if (error instanceof String text) code = text;
        if (code.equals("null")) code = "provider_error";
        String message =
                switch (code) {
                    case "subscription_sharing_user_not_eligible" ->
                            "This account/workspace is not eligible for ChatGPT plan usage.";
                    case "subscription_sharing_usage_limit_exceeded" ->
                            "ChatGPT app usage limit reached. Review ChatGPT Settings → Usage.";
                    case "subscription_sharing_usage_unavailable",
                            "subscription_sharing_user_unavailable" ->
                            "ChatGPT usage is temporarily unavailable; retry later.";
                    case "subscription_sharing_unsupported_capability" ->
                            "Unsupported capability; change the model or request feature.";
                    case "subscription_sharing_route_not_supported" ->
                            "This route is not authorized for ChatGPT plan usage.";
                    case "subscription_sharing_invalid_user" ->
                            "ChatGPT account could not be validated; check the account and reconnect.";
                    case "invalid_grant",
                            "invalid_refresh_token",
                            "token_expired",
                            "refresh_token_expired",
                            "refresh_token_invalidated",
                            "refresh_token_reused" ->
                            "Credentials are no longer usable; reconnect using the saved account.";
                    default ->
                            switch (status) {
                                case 401 ->
                                        "Authentication rejected; check credentials and reconnect if revoked.";
                                case 403 -> "Permission, region or policy prevented this request.";
                                case 429 -> "Usage limit reached; retry later.";
                                case 503 ->
                                        "Provider temporarily unavailable; credentials retained.";
                                default -> "Provider rejected the request.";
                            };
                };
        String diagnostic =
                detail.get("message") instanceof String s
                        ? s
                        : body.get("detail") instanceof String s ? s : "";
        String param = detail.get("param") instanceof String s ? s : "";
        String combined = code + " " + diagnostic + " " + param;
        for (var secret : secrets)
            if (secret != null && !secret.isEmpty()) {
                combined = combined.replace(secret, "[redacted]");
                if (code.contains(secret)) code = "provider_error";
                if (requestId != null) requestId = requestId.replace(secret, "[redacted]");
            }
        return new ProviderException(
                status,
                code.matches("[a-z_]{1,100}") ? code : "provider_error",
                message + " " + combined,
                requestId);
    }

    public static String safe(String text) {
        if (text == null) return "";
        var result =
                text.replaceAll("[\\x00-\\x1f\\x7f-\\x9f]", " ")
                        .replaceAll("(?i)(bearer\\s+|sk-)[A-Za-z0-9._~-]+", "[redacted]")
                        .replaceAll(
                                "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+",
                                "[redacted]");
        return result.substring(0, Math.min(500, result.length()));
    }
}
