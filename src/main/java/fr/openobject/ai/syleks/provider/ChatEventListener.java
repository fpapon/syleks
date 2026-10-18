package fr.openobject.ai.syleks.provider;

@FunctionalInterface
public interface ChatEventListener {
    void onEvent(ChatEvent event);
}
