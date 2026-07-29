package ru.guchicraft.builder;

import java.util.List;

public record ValidationResult(
        List<ValidationIssue> issues,
        long totalFiles,
        long totalBytes,
        long mods,
        long configs,
        long resourcepacks
) {
    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR);
    }

    public long warningCount() {
        return issues.stream().filter(issue -> issue.severity() == ValidationIssue.Severity.WARNING).count();
    }

    public long errorCount() {
        return issues.stream().filter(issue -> issue.severity() == ValidationIssue.Severity.ERROR).count();
    }
}
