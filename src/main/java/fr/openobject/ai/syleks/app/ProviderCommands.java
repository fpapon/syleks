package fr.openobject.ai.syleks.app;

import fr.openobject.ai.syleks.auth.*;
import fr.openobject.ai.syleks.provider.*;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.io.PrintStream;
import java.util.Arrays;

/** Non-interactive command routing shares exactly the authentication used by the TUI. */
@DefaultScoped
public class ProviderCommands {
    public static final String CLOUD_NOTICE =
            "Cloud provider: messages, necessary history, instructions and approved tool results leave your local environment.";
    private final ProviderService providers;
    private final AuthenticationService auth;
    private final BrowserLauncher browser;

    public ProviderCommands(
            ProviderService providers, AuthenticationService auth, BrowserLauncher browser) {
        this.providers = providers;
        this.auth = auth;
        this.browser = browser;
    }

    public static boolean handles(String[] args) {
        return args.length > 0 && java.util.Set.of("auth", "provider", "model").contains(args[0]);
    }

    public void run(String[] args, PrintStream out) throws Exception {
        if (args.length < 2) throw usage();
        switch (args[0] + " " + args[1]) {
            case "provider list" -> {
                require(args.length == 2);
                for (var p : providers.registry().providers())
                    out.println(
                            (providers.active().providerId().equals(p.id()) ? "* " : "  ")
                                    + p.id()
                                    + "\t"
                                    + ProviderException.safe(p.displayName())
                                    + "\t"
                                    + (p.capabilities().local() ? "Local" : "Cloud"));
            }
            case "provider use" -> {
                require(args.length == 3);
                providers.useProvider(args[2]);
                if (!providers.registry().get(args[2]).capabilities().local())
                    out.println(CLOUD_NOTICE);
                out.println("Selected " + providers.selection());
            }
            case "model list" -> {
                require(args.length == 2);
                out.println("Current: " + providers.selection());
                for (var model : providers.models(true))
                    out.println(
                            model.selection() + "\t" + ProviderException.safe(model.displayName()));
            }
            case "model use" -> {
                require(args.length == 3);
                providers.useModel(args[2]);
                if (!providers
                        .registry()
                        .get(providers.active().providerId())
                        .capabilities()
                        .local()) out.println(CLOUD_NOTICE);
                out.println("Selected " + providers.selection());
            }
            case "auth status", "auth accounts" -> {
                require(args.length == 2);
                out.println(
                        "Active provider: "
                                + providers.active().providerId()
                                + " · "
                                + providers.selection());
                for (var c : auth.accounts())
                    out.println(
                            (c.account().id().equals(providers.active().accountId()) ? "* " : "  ")
                                    + c.account().providerId()
                                    + "\t"
                                    + ProviderException.safe(c.account().label())
                                    + "\t"
                                    + status(c));
            }
            case "auth login" -> {
                require(args.length == 3 || args.length == 4);
                out.println(CLOUD_NOTICE);
                Credentials credentials;
                if (args[2].equals("chatgpt")) {
                    credentials =
                            auth.authenticate(
                                    args.length == 4 ? args[3] : null,
                                    attempt -> {
                                        out.println(
                                                "Continue with ChatGPT. Waiting for authorization (Ctrl+C cancels).");
                                        out.println(
                                                "Open manually if needed: " + attempt.manualUri());
                                        browser.open(attempt.authorizationUri());
                                    });
                } else if (args[2].equals("openai-api") && args.length == 3) {
                    var console = System.console();
                    String key;
                    if (console != null) {
                        char[] secret =
                                console.readPassword("OpenAI API key (separate API billing): ");
                        if (secret == null)
                            throw new IllegalArgumentException("API key entry cancelled");
                        key = new String(secret);
                        Arrays.fill(secret, '\0');
                    } else {
                        key = System.getenv("OPENAI_API_KEY");
                        if (key == null)
                            throw new IllegalArgumentException(
                                    "Use an interactive terminal or explicitly provide OPENAI_API_KEY for this login command");
                    }
                    credentials = auth.apiKey(key);
                } else throw usage();
                out.println(
                        "Connected: "
                                + ProviderException.safe(credentials.account().label())
                                + " · "
                                + status(credentials));
                try {
                    providers.useAccount(credentials.account().id());
                } catch (Exception e) {
                    out.println(
                            "Credentials saved. Model selection pending: "
                                    + ProviderException.safe(e.getMessage()));
                }
            }
            case "auth use" -> {
                require(args.length == 3);
                providers.useAccount(args[2]);
                out.println("Selected account " + args[2]);
                out.println(CLOUD_NOTICE);
            }
            case "auth logout" -> {
                require(args.length == 3 || args.length == 4);
                String id = args.length == 4 ? args[3] : providers.accountFor(args[2] + "/model");
                if (id == null)
                    throw new IllegalArgumentException(
                            "No selected account for this provider; specify its account ID");
                var selected =
                        auth.accounts().stream()
                                .filter(
                                        c ->
                                                c.account().id().equals(id)
                                                        && c.account().providerId().equals(args[2]))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Account/provider mismatch"));
                boolean revoked = providers.logout(selected.account().id());
                out.println(
                        "Signed out locally. "
                                + (selected.account().providerId().equals("openai-api")
                                        ? "API key removed; revoke it separately in OpenAI API settings."
                                        : revoked
                                                ? "Renewable session revoked."
                                                : "Remote revocation was not confirmed; disconnect Syleks in ChatGPT Settings."));
            }
            default -> throw usage();
        }
    }

    public static String status(Credentials c) {
        if (!c.connected()) return "Signed out";
        return c.account().providerId().equals("chatgpt")
                ? "Plan usage: "
                        + (c.planAuthorized() ? "Authorized" : "Disabled (permission missing)")
                : "API key configured · separate API billing";
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "Use auth login|logout PROVIDER [ACCOUNT-ID], auth status|accounts|use ACCOUNT-ID, provider list|use ID, model list|use ID");
    }

    private static void require(boolean valid) {
        if (!valid) throw usage();
    }
}
