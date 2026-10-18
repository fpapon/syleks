package fr.openobject.ai.syleks.usage;

import fr.openobject.ai.syleks.auth.CredentialStore;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.*;

@ApplicationScoped
public class UsagePreferences {
    public enum Visibility {
        AUTO,
        SHOW,
        HIDE
    }

    private final CredentialStore store;
    private volatile Visibility visibility = Visibility.AUTO;
    private double warning = 70, critical = 90;

    public UsagePreferences(CredentialStore store) {
        this.store = store;
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Init
    public void load() {
        try {
            var state = store.read(store.root().resolve("config.json"));
            if (state.get("sidebar") instanceof String value)
                visibility = Visibility.valueOf(value.toUpperCase(Locale.ROOT));
            if (state.get("context_warning_percent") instanceof Number n) warning = n.doubleValue();
            if (state.get("context_critical_percent") instanceof Number n)
                critical = n.doubleValue();
            if (!(warning >= 0 && warning < critical && critical <= 100))
                throw new IllegalArgumentException(
                        "Expected 0 <= context_warning_percent < context_critical_percent <= 100");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot load usage settings", e);
        }
    }

    public Visibility visibility() {
        return visibility;
    }

    public double warning() {
        return warning;
    }

    public double critical() {
        return critical;
    }

    public void select(String argument) throws Exception {
        var next =
                argument.isBlank()
                        ? visibility == Visibility.HIDE ? Visibility.SHOW : Visibility.HIDE
                        : Visibility.valueOf(argument.toUpperCase(Locale.ROOT));
        store.locked(
                "configuration",
                () -> {
                    var file = store.root().resolve("config.json");
                    var state = new LinkedHashMap<>(store.read(file));
                    state.put("sidebar", next.name());
                    store.write(file, state);
                    return null;
                });
        visibility = next;
    }
}
