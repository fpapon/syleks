package fr.openobject.ai.syleks.provider;

/** Unknown model-specific capabilities are deliberately not inferred from a model name. */
public record ProviderCapabilities(boolean local, boolean streaming, boolean functionTools) {}
