package fr.openobject.ai.syleks.model;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public record ChatMessage(String role, String content, String time, String displayContent) {
    public ChatMessage(String role, String content, String time) {
        this(role, content, time, content);
    }

    public static ChatMessage now(String role, String content, String displayContent) {
        return new ChatMessage(role, content, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), displayContent);
    }

    public static ChatMessage now(String role, String content) {
        return new ChatMessage(role, content, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
    }
}
