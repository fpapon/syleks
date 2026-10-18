package fr.openobject.ai.syleks.provider;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.auth.*;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ProviderService {
    private final ProviderRegistry registry;
    private final CredentialStore store;
    private final AuthenticationService auth;
    private final AppConfig config;
    private final JsonMapper json;
    private final Map<String, List<LlmModel>> catalogs = new ConcurrentHashMap<>();
    private volatile ProviderConfiguration active;
    private volatile String unavailableSelection;
    private final Map<String, String> accounts = new ConcurrentHashMap<>();
    private final Map<String, String> accountStatuses = new ConcurrentHashMap<>();

    public ProviderService(
            ProviderRegistry registry,
            CredentialStore store,
            AuthenticationService auth,
            AppConfig config,
            JsonMapper json) {
        this.registry = registry;
        this.store = store;
        this.auth = auth;
        this.config = config;
        this.json = json;
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Init
    void initialize() {
        try {
            var saved = store.read(store.root().resolve("config.json"));
            if (saved.get("accounts") instanceof Map<?, ?> values)
                values.forEach(
                        (k, v) -> {
                            if (v instanceof String s) accounts.put(String.valueOf(k), s);
                        });
            String model = CredentialStore.str(saved, "model");
            String provider = CredentialStore.str(saved, "provider");
            if (provider == null) {
                provider = registry.providerId(config.defaultModel());
                model = registry.modelId(config.defaultModel());
            }
            active =
                    new ProviderConfiguration(
                            provider, model == null ? "" : model, accounts.get(provider));
            try {
                registry.get(provider);
            } catch (IllegalArgumentException unavailable) {
                unavailableSelection = selection();
            }
            try {
                for (var credentials : auth.accounts()) remember(credentials);
            } catch (IOException ignored) {
                /* A locked cloud keyring must not prevent local Ollama use. */
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load provider selection", e);
        }
    }

    public ProviderRegistry registry() {
        return registry;
    }

    public ProviderConfiguration active() {
        return active;
    }

    public String selection() {
        var a = active;
        return a.providerId() + "/" + (a.modelId().isBlank() ? "select-a-model" : a.modelId());
    }

    public String accountFor(String selection) {
        return accounts.get(registry.providerId(selection));
    }

    public List<Credentials> accounts() throws IOException {
        return auth.accounts();
    }

    public void checkCloud(String provider) throws IOException {
        if (registry.get(provider).capabilities().local()) return;
        if ("false".equalsIgnoreCase(System.getenv("SYLEKS_ALLOW_CLOUD")))
            throw new IOException("Cloud providers are disabled by SYLEKS_ALLOW_CLOUD");
        // Every ancestor may restrict use; a child cannot relax its parent's project policy.
        for (Path dir = config.workspace(); dir != null; dir = dir.getParent()) {
            Path file = dir.resolve(".syleks/project.json");
            if (Files.exists(file)) {
                Map<?, ?> policy;
                try {
                    policy = json.fromString(Map.class, Files.readString(file));
                } catch (RuntimeException e) {
                    throw new IOException("Invalid project cloud policy");
                }
                if (policy.containsKey("allow_cloud")
                        && !Boolean.TRUE.equals(policy.get("allow_cloud")))
                    throw new IOException("Cloud providers are disabled by " + file);
            }
        }
    }

    public List<LlmModel> models(boolean refresh) throws Exception {
        var a = active;
        return models(a.providerId(), a.accountId(), refresh);
    }

    private List<LlmModel> models(String provider, String account, boolean refresh)
            throws Exception {
        checkCloud(provider);
        String key = provider + ":" + Objects.toString(account, "local");
        if (refresh || !catalogs.containsKey(key))
            catalogs.put(key, registry.get(provider).listModels(account));
        return catalogs.get(key);
    }

    private void save(ProviderConfiguration value) throws Exception {
        store.locked(
                "configuration",
                () -> {
                    var state =
                            new LinkedHashMap<>(store.read(store.root().resolve("config.json")));
                    var savedAccounts = new LinkedHashMap<String, Object>();
                    if (state.get("accounts") instanceof Map<?, ?> existing)
                        existing.forEach((k, v) -> savedAccounts.put(String.valueOf(k), v));
                    if (value.accountId() != null)
                        savedAccounts.put(value.providerId(), value.accountId());
                    state.put("provider", value.providerId());
                    state.put("model", value.modelId());
                    state.put("accounts", savedAccounts);
                    store.write(store.root().resolve("config.json"), state);
                    return null;
                });
        active = value;
        unavailableSelection = null;
        if (value.accountId() != null) accounts.put(value.providerId(), value.accountId());
    }

    public void useProvider(String provider) throws Exception {
        registry.get(provider);
        checkCloud(provider);
        String account = accounts.get(provider);
        List<LlmModel> models = models(provider, account, true);
        if (models.isEmpty()) throw new IOException("Provider has no selectable models");
        var old = active;
        String model =
                old.providerId().equals(provider)
                                && models.stream().anyMatch(m -> m.id().equals(old.modelId()))
                        ? old.modelId()
                        : models.getFirst().id();
        save(new ProviderConfiguration(provider, model, account));
    }

    public void useModel(String selection) throws Exception {
        String qualified =
                selection.contains("/") ? selection : active.providerId() + "/" + selection;
        config.resolveModel(qualified);
        String provider = registry.providerId(qualified), model = registry.modelId(qualified);
        checkCloud(provider);
        String account = accounts.get(provider);
        if (!provider.equals("ollama")
                && models(provider, account, true).stream().noneMatch(m -> m.id().equals(model)))
            throw new IOException("Model unavailable for this account; refresh /models");
        save(new ProviderConfiguration(provider, model, account));
    }

    public void useAccount(String id) throws Exception {
        var previous = active;
        var c = store.load(id);
        String provider = c.account().providerId();
        remember(c);
        checkCloud(provider);
        if (!c.connected())
            throw new IOException("Account signed out; reconnect with its saved account ID");
        // Save identity-only accounts without claiming plan permission or attempting inference.
        if (provider.equals("chatgpt") && !c.planAuthorized()) {
            save(new ProviderConfiguration(provider, "", id));
            return;
        }
        // Credentials remain selected if the catalog is temporarily unavailable after sign-in.
        save(new ProviderConfiguration(provider, "", id));
        var models = models(provider, id, true);
        if (models.isEmpty())
            throw new IOException("Account connected, but no selectable models are available");
        String model =
                previous.providerId().equals(provider)
                                && models.stream().anyMatch(m -> m.id().equals(previous.modelId()))
                        ? previous.modelId()
                        : models.getFirst().id();
        save(new ProviderConfiguration(provider, model, id));
    }

    public void restore(String selection, String accountId) throws Exception {
        unavailableSelection = selection;
        String provider = registry.providerId(selection);
        registry.get(provider);
        config.resolveModel(selection);
        active = new ProviderConfiguration(provider, registry.modelId(selection), accountId);
        if (provider.equals("chatgpt") || provider.equals("openai-api")) {
            if (accountId == null) accounts.remove(provider);
            else accounts.put(provider, accountId);
            if (accountId == null)
                throw new IOException("Session account is missing; select one with /accounts");
            var c = store.load(accountId);
            remember(c);
            if (!provider.equals(c.account().providerId()) || !c.connected())
                throw new IOException(
                        "Session account is unavailable; choose an account or /connect");
            accounts.put(provider, accountId);
        }
        // Restoring a session is an in-memory selection, not a rewrite of global defaults.
        active = new ProviderConfiguration(provider, registry.modelId(selection), accountId);
        unavailableSelection = null;
    }

    public void restore(String savedProvider, String selection, String accountId) throws Exception {
        unavailableSelection = selection;
        String provider = registry.providerId(selection);
        if (!savedProvider.equals(provider)
                && !savedProvider.equals(registry.get(provider).displayName()))
            throw new IOException(
                    "Saved provider unavailable: "
                            + savedProvider
                            + "; select a replacement with /providers");
        restore(selection, accountId);
    }

    public String connectionStatus(String selection) {
        if (selection.equals(unavailableSelection)) return "Unavailable · /providers";
        String id = registry.providerId(selection);
        if (registry.get(id).capabilities().local()) return "Local";
        if (!id.equals("chatgpt") && !id.equals("openai-api")) return "Configured";
        String account = accounts.get(id);
        return account == null
                ? "Disconnected"
                : accountStatuses.getOrDefault(account, "Disconnected");
    }

    private void remember(Credentials c) {
        accountStatuses.put(
                c.account().id(),
                !c.connected()
                        ? "Disconnected"
                        : c.account().providerId().equals("chatgpt") && !c.planAuthorized()
                                ? "Plan usage disabled"
                                : "Connected");
    }

    public boolean logout(String id) throws Exception {
        boolean revoked = auth.logout(id);
        remember(store.load(id));
        return revoked;
    }

    public void stream(ChatRequest request, ChatEventListener listener) throws Exception {
        if (request.model().equals(unavailableSelection))
            throw new IOException(
                    "Saved provider/account unavailable; explicitly select an account, model or provider");
        String provider = registry.providerId(request.model());
        checkCloud(provider);
        String model = registry.modelId(request.model());
        if ((provider.equals("chatgpt") || provider.equals("openai-api"))
                && models(provider, request.accountId(), true).stream()
                        .noneMatch(m -> m.id().equals(model)))
            throw new IOException(
                    "Saved model unavailable; refresh /models and select a replacement");
        try {
            registry.get(provider)
                    .chat(
                            new ChatRequest(
                                    model,
                                    request.agent(),
                                    request.accountId(),
                                    request.messages(),
                                    request.tools(),
                                    request.observer()),
                            listener);
        } finally {
            if (request.accountId() != null)
                try {
                    remember(store.load(request.accountId()));
                } catch (Exception ignored) {
                }
        }
    }
}
