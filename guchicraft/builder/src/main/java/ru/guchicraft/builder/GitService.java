package ru.guchicraft.builder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GitService {
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    public RepositoryInfo inspect(Path repository) throws IOException {
        if (!Files.isDirectory(repository.resolve(".git"))) {
            throw new IOException("Выбранная папка не является локальным Git-репозиторием: отсутствует .git");
        }
        run(repository, List.of("git", "--version"), true);
        String branch = run(repository, List.of("git", "branch", "--show-current"), true).output().trim();
        String remote = run(repository, List.of("git", "remote", "get-url", "origin"), true).output().trim();
        String status = run(repository, List.of("git", "status", "--short", "--", "launcher"), true).output();
        return new RepositoryInfo(branch, remote, status);
    }

    public PublishResult publish(Path repository, String branch, String commitMessage) throws IOException {
        String cleanBranch = requireValue(branch, "Не указана ветка Git.");
        String cleanMessage = requireValue(commitMessage, "Напиши сообщение коммита.");

        run(repository, List.of("git", "add", "--", "launcher"), true);
        CommandResult staged = run(repository, List.of("git", "diff", "--cached", "--name-only", "--", "launcher"), true);
        if (staged.output().isBlank()) {
            return new PublishResult(false, "Изменений для публикации нет.", "");
        }

        CommandResult commit = run(repository, List.of("git", "commit", "-m", cleanMessage), true);
        CommandResult push = run(repository, List.of("git", "push", "origin", cleanBranch), true);
        return new PublishResult(true, "Commit и Push выполнены успешно.", commit.output() + System.lineSeparator() + push.output());
    }

    private static CommandResult run(Path directory, List<String> command, boolean requireSuccess) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append(System.lineSeparator());
        }

        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Команда Git была прервана.", exception);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Git не ответил за " + TIMEOUT.toMinutes() + " минуты.");
        }
        int exit = process.exitValue();
        String text = output.toString().trim();
        if (requireSuccess && exit != 0) {
            throw new IOException("Ошибка Git (код " + exit + "):\n" + (text.isBlank() ? "Нет текста ошибки" : text));
        }
        return new CommandResult(exit, text);
    }

    private static String requireValue(String value, String message) throws IOException {
        if (value == null || value.isBlank()) throw new IOException(message);
        return value.trim();
    }

    public record RepositoryInfo(String branch, String remote, String status) {}
    public record PublishResult(boolean published, String message, String details) {}
    private record CommandResult(int exitCode, String output) {}
}
