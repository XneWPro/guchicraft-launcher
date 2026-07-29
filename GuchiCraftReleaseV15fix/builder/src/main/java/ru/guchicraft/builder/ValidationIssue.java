package ru.guchicraft.builder;

public record ValidationIssue(Severity severity, String message) {
    public enum Severity { ERROR, WARNING, INFO }
}
