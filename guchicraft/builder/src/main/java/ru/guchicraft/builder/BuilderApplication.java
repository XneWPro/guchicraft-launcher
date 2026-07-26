package ru.guchicraft.builder;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

public final class BuilderApplication extends Application {
    private final ManifestBuilder manifestBuilder = new ManifestBuilder();
    private final BuildValidator validator = new BuildValidator();
    private final GitService gitService = new GitService();
    private final Preferences preferences = Preferences.userNodeForPackage(BuilderApplication.class);

    private TextField repositoryField;
    private TextField buildVersionField;
    private TextField minecraftField;
    private TextField fabricField;
    private TextField serverField;
    private TextField javaVersionField;
    private TextField minimumMemoryField;
    private TextField defaultMemoryField;
    private TextField maximumMemoryField;
    private TextField branchField;
    private TextField commitMessageField;
    private CheckBox removeUnknownCheck;
    private Label statusLabel;
    private Label statisticsLabel;
    private ProgressIndicator progress;
    private Button validateButton;
    private Button buildButton;
    private Button openManifestButton;
    private Button publishButton;
    private TextArea reportArea;
    private Path generatedManifest;
    private ValidationResult lastValidation;

    @Override
    public void start(Stage stage) {
        Label title = new Label("ГУЧИКРАФТ BUILDER v4");
        title.getStyleClass().add("title");
        Label subtitle = new Label("Полное управление клиентской сборкой: игра, Fabric, Java, память и файлы");
        subtitle.getStyleClass().add("subtitle");

        repositoryField = new TextField(preferences.get("repository", ""));
        repositoryField.setPromptText("Например: C:\\GitHub\\guchicraft-launcher-files");
        Button chooseButton = new Button("Выбрать папку");
        chooseButton.setOnAction(event -> chooseRepository(stage));
        HBox repositoryRow = new HBox(10, repositoryField, chooseButton);
        HBox.setHgrow(repositoryField, Priority.ALWAYS);

        buildVersionField = new TextField(preferences.get("buildVersion", "1.0.0"));
        minecraftField = new TextField(preferences.get("minecraft", "26.2"));
        fabricField = new TextField(preferences.get("fabric", "0.19.3"));
        serverField = new TextField(preferences.get("server", "guchicraft.peniscraft.pro"));
        javaVersionField = new TextField(preferences.get("javaVersion", "25"));
        minimumMemoryField = new TextField(preferences.get("minimumMemory", "2048"));
        defaultMemoryField = new TextField(preferences.get("defaultMemory", "4096"));
        maximumMemoryField = new TextField(preferences.get("maximumMemory", "16384"));
        removeUnknownCheck = new CheckBox("Удалять неизвестные файлы из управляемых папок");
        removeUnknownCheck.setSelected(preferences.getBoolean("removeUnknown", true));
        branchField = new TextField(preferences.get("branch", "main"));
        commitMessageField = new TextField(preferences.get("commitMessage", "Обновление клиентской сборки"));

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.add(new Label("Папка репозитория"), 0, 0);
        form.add(repositoryRow, 1, 0);
        form.add(new Label("Версия сборки"), 0, 1);
        form.add(buildVersionField, 1, 1);
        form.add(new Label("Minecraft"), 0, 2);
        form.add(minecraftField, 1, 2);
        form.add(new Label("Fabric Loader"), 0, 3);
        form.add(fabricField, 1, 3);
        form.add(new Label("Java (основная версия)"), 0, 4);
        form.add(javaVersionField, 1, 4);
        form.add(new Label("Минимальная память, МБ"), 0, 5);
        form.add(minimumMemoryField, 1, 5);
        form.add(new Label("Память по умолчанию, МБ"), 0, 6);
        form.add(defaultMemoryField, 1, 6);
        form.add(new Label("Максимальная память, МБ"), 0, 7);
        form.add(maximumMemoryField, 1, 7);
        form.add(new Label("Адрес сервера"), 0, 8);
        form.add(serverField, 1, 8);
        form.add(removeUnknownCheck, 1, 9);
        form.add(new Label("Ветка Git"), 0, 10);
        form.add(branchField, 1, 10);
        form.add(new Label("Сообщение коммита"), 0, 11);
        form.add(commitMessageField, 1, 11);
        ColumnConstraints first = new ColumnConstraints();
        first.setMinWidth(170);
        ColumnConstraints second = new ColumnConstraints();
        second.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(first, second);

        validateButton = new Button("ПРОВЕРИТЬ СБОРКУ");
        validateButton.getStyleClass().add("secondary-primary");
        validateButton.setMaxWidth(Double.MAX_VALUE);
        validateButton.setOnAction(event -> validateBuild(false));

        buildButton = new Button("СОБРАТЬ MANIFEST");
        buildButton.getStyleClass().add("primary");
        buildButton.setMaxWidth(Double.MAX_VALUE);
        buildButton.setOnAction(event -> validateBuild(true));
        publishButton = new Button("СОБРАТЬ И ОПУБЛИКОВАТЬ");
        publishButton.getStyleClass().add("publish");
        publishButton.setMaxWidth(Double.MAX_VALUE);
        publishButton.setOnAction(event -> validateBuildAndPublish());

        HBox mainButtons = new HBox(10, validateButton, buildButton, publishButton);
        HBox.setHgrow(validateButton, Priority.ALWAYS);
        HBox.setHgrow(buildButton, Priority.ALWAYS);
        HBox.setHgrow(publishButton, Priority.ALWAYS);

        Button openRepoButton = new Button("Открыть репозиторий");
        openRepoButton.setOnAction(event -> openRepository());
        openManifestButton = new Button("Открыть manifest.json");
        openManifestButton.setDisable(true);
        openManifestButton.setOnAction(event -> openGeneratedManifest());
        HBox actionRow = new HBox(10, openRepoButton, openManifestButton);

        progress = new ProgressIndicator();
        progress.setVisible(false);
        progress.setPrefSize(26, 26);
        statusLabel = new Label("Нажми «Проверить сборку» перед созданием manifest.json.");
        statusLabel.setWrapText(true);
        statisticsLabel = new Label("Файлов пока не просканировано");
        statisticsLabel.getStyleClass().add("statistics");
        HBox statusRow = new HBox(12, progress, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        reportArea = new TextArea();
        reportArea.setEditable(false);
        reportArea.setWrapText(true);
        reportArea.setPrefRowCount(9);
        reportArea.setPromptText("Здесь появится отчёт проверки...");
        reportArea.getStyleClass().add("report-area");

        VBox card = new VBox(16, form, mainButtons, actionRow, new Separator(), statusRow, statisticsLabel,
                new Label("Отчёт проверки"), reportArea);
        card.getStyleClass().add("card");

        Label hint = new Label("Критические ошибки блокируют создание manifest.json. Предупреждения не блокируют сборку, но их желательно проверить.");
        hint.setWrapText(true);
        hint.getStyleClass().add("hint");

        VBox root = new VBox(10, title, subtitle, card, hint);
        root.setPadding(new Insets(24));
        root.getStyleClass().add("root-pane");

        Scene scene = new Scene(root, 980, 960);
        scene.getStylesheets().add(getClass().getResource("/builder.css").toExternalForm());
        stage.setTitle("ГУЧИКРАФТ Builder v4");
        stage.setMinWidth(900);
        stage.setMinHeight(860);
        stage.setScene(scene);
        stage.show();
    }

    private void validateBuild(boolean buildAfterValidation) {
        Path repository = getRepository();
        if (repository == null) return;
        try { readRuntimeSettings(); } catch (IllegalArgumentException exception) { showError(exception.getMessage()); return; }
        savePreferences();
        setBusy(true, "Проверяю структуру, JAR-файлы и дубликаты...");

        Task<ValidationResult> task = new Task<>() {
            @Override protected ValidationResult call() throws Exception { return validator.validate(repository); }
        };
        task.setOnSucceeded(event -> {
            lastValidation = task.getValue();
            showValidation(lastValidation);
            if (lastValidation.hasErrors()) {
                setBusy(false, "Проверка завершена: найдены критические ошибки.");
                if (buildAfterValidation) showError("Manifest не создан. Исправь ошибки с отметкой [ОШИБКА] и повтори проверку.");
            } else if (buildAfterValidation) {
                buildManifest(repository);
            } else {
                setBusy(false, lastValidation.warningCount() == 0
                        ? "Сборка прошла проверку и готова к созданию manifest.json."
                        : "Проверка завершена. Есть предупреждения, но manifest можно создать.");
            }
        });
        task.setOnFailed(event -> {
            setBusy(false, "Ошибка проверки сборки.");
            showError(message(task.getException()));
        });
        startTask(task, "build-validator");
    }

    private void buildManifest(Path repository) {
        setBusy(true, "Считаю SHA-256 и создаю manifest.json...");
        Task<ManifestBuilder.BuildResult> task = new Task<>() {
            @Override protected ManifestBuilder.BuildResult call() throws Exception {
                return manifestBuilder.build(repository, buildVersionField.getText(), minecraftField.getText(),
                        fabricField.getText(), readRuntimeSettings().javaVersion(),
                        readRuntimeSettings().minimumMemoryMb(), readRuntimeSettings().defaultMemoryMb(),
                        readRuntimeSettings().maximumMemoryMb(), serverField.getText(), removeUnknownCheck.isSelected());
            }
        };
        task.setOnSucceeded(event -> {
            var result = task.getValue();
            generatedManifest = result.output();
            openManifestButton.setDisable(false);
            statisticsLabel.setText(statistics(result.totalFiles(), result.totalBytes(), result.mods(), result.configs(), result.resourcepacks()));
            setBusy(false, "Готово: manifest.json создан. Теперь сделай Commit и Push в GitHub Desktop.");
        });
        task.setOnFailed(event -> {
            setBusy(false, "Ошибка создания manifest.json.");
            showError(message(task.getException()));
        });
        startTask(task, "manifest-builder");
    }

    private void validateBuildAndPublish() {
        Path repository = getRepository();
        if (repository == null) return;
        try { readRuntimeSettings(); } catch (IllegalArgumentException exception) { showError(exception.getMessage()); return; }
        savePreferences();
        setBusy(true, "Проверяю сборку перед публикацией...");
        Task<ValidationResult> task = new Task<>() {
            @Override protected ValidationResult call() throws Exception { return validator.validate(repository); }
        };
        task.setOnSucceeded(event -> {
            lastValidation = task.getValue();
            showValidation(lastValidation);
            if (lastValidation.hasErrors()) {
                setBusy(false, "Публикация остановлена: исправь критические ошибки.");
                showError("Нельзя публиковать сборку с ошибками [ОШИБКА].");
            } else {
                buildManifestAndPublish(repository);
            }
        });
        task.setOnFailed(event -> {
            setBusy(false, "Ошибка проверки перед публикацией.");
            showError(message(task.getException()));
        });
        startTask(task, "publish-validator");
    }

    private void buildManifestAndPublish(Path repository) {
        setBusy(true, "Создаю manifest.json...");
        Task<GitService.PublishResult> task = new Task<>() {
            @Override protected GitService.PublishResult call() throws Exception {
                RuntimeSettings runtime = readRuntimeSettings();
                ManifestBuilder.BuildResult build = manifestBuilder.build(repository, buildVersionField.getText(),
                        minecraftField.getText(), fabricField.getText(), runtime.javaVersion(),
                        runtime.minimumMemoryMb(), runtime.defaultMemoryMb(), runtime.maximumMemoryMb(),
                        serverField.getText(), removeUnknownCheck.isSelected());
                generatedManifest = build.output();
                GitService.RepositoryInfo info = gitService.inspect(repository);
                String branch = branchField.getText().isBlank() ? info.branch() : branchField.getText().trim();
                return gitService.publish(repository, branch, commitMessageField.getText());
            }
        };
        task.setOnSucceeded(event -> {
            GitService.PublishResult result = task.getValue();
            openManifestButton.setDisable(generatedManifest == null);
            String details = result.details().isBlank() ? result.message() : result.message() + "\n\n" + result.details();
            reportArea.setText(details);
            setBusy(false, result.published()
                    ? "Готово: manifest создан, изменения отправлены на GitHub."
                    : "Manifest создан, но новых изменений для Commit нет.");
        });
        task.setOnFailed(event -> {
            setBusy(false, "Не удалось опубликовать сборку.");
            showError(message(task.getException()) + "\n\nПроверь, что Git установлен, GitHub Desktop авторизован и репозиторий клонирован.");
        });
        startTask(task, "manifest-publisher");
    }

    private void showValidation(ValidationResult result) {
        statisticsLabel.setText(statistics(result.totalFiles(), result.totalBytes(), result.mods(), result.configs(), result.resourcepacks())
                + "   •   ошибок: " + result.errorCount() + "   •   предупреждений: " + result.warningCount());
        StringBuilder text = new StringBuilder();
        for (ValidationIssue issue : result.issues()) {
            String prefix = switch (issue.severity()) {
                case ERROR -> "[ОШИБКА] ";
                case WARNING -> "[ВНИМАНИЕ] ";
                case INFO -> "[ИНФО] ";
            };
            text.append(prefix).append(issue.message()).append(System.lineSeparator());
        }
        reportArea.setText(text.toString());
        reportArea.positionCaret(0);
    }

    private Path getRepository() {
        Path repository = safePath(repositoryField.getText());
        if (repository == null || !Files.isDirectory(repository)) {
            showError("Сначала выбери существующую корневую папку репозитория guchicraft-launcher-files.");
            return null;
        }
        return repository;
    }

    private void chooseRepository(Stage stage) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Выбери папку guchicraft-launcher-files");
        Path current = safePath(repositoryField.getText());
        if (current != null && Files.isDirectory(current)) chooser.setInitialDirectory(current.toFile());
        var selected = chooser.showDialog(stage);
        if (selected != null) {
            repositoryField.setText(selected.getAbsolutePath());
            lastValidation = null;
        }
    }

    private void setBusy(boolean busy, String message) {
        Platform.runLater(() -> {
            validateButton.setDisable(busy);
            buildButton.setDisable(busy);
        publishButton.setDisable(busy);
            progress.setVisible(busy);
            statusLabel.setText(message);
        });
    }

    private void openRepository() {
        Path path = getRepository();
        if (path != null) openPath(path);
    }

    private void openGeneratedManifest() {
        Path repository = getRepository();
        Path manifest = generatedManifest != null ? generatedManifest : repository == null ? null : repository.resolve("launcher/manifest.json");
        if (manifest == null || !Files.exists(manifest)) {
            showError("Сначала создай manifest.json.");
            return;
        }
        openPath(manifest);
    }

    private void openPath(Path path) {
        try { Desktop.getDesktop().open(path.toFile()); }
        catch (IOException | UnsupportedOperationException exception) { showError("Не получилось открыть: " + path); }
    }

    private void savePreferences() {
        preferences.put("repository", repositoryField.getText().trim());
        preferences.put("buildVersion", buildVersionField.getText().trim());
        preferences.put("minecraft", minecraftField.getText().trim());
        preferences.put("fabric", fabricField.getText().trim());
        preferences.put("server", serverField.getText().trim());
        preferences.put("javaVersion", javaVersionField.getText().trim());
        preferences.put("minimumMemory", minimumMemoryField.getText().trim());
        preferences.put("defaultMemory", defaultMemoryField.getText().trim());
        preferences.put("maximumMemory", maximumMemoryField.getText().trim());
        preferences.putBoolean("removeUnknown", removeUnknownCheck.isSelected());
        preferences.put("branch", branchField.getText().trim());
        preferences.put("commitMessage", commitMessageField.getText().trim());
    }

    private static void startTask(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static String statistics(long files, long bytes, long mods, long configs, long packs) {
        return "Всего: %d файлов (%s)   •   моды: %d   •   конфиги: %d   •   ресурспаки: %d"
                .formatted(files, humanSize(bytes), mods, configs, packs);
    }

    private static Path safePath(String text) {
        try { return text == null || text.isBlank() ? null : Path.of(text.trim()).toAbsolutePath().normalize(); }
        catch (Exception ignored) { return null; }
    }

    private static String humanSize(long bytes) {
        double value = bytes;
        String[] units = {"Б", "КБ", "МБ", "ГБ"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return unit == 0 ? "%d %s".formatted(bytes, units[unit]) : "%.2f %s".formatted(value, units[unit]);
    }

    private static String message(Throwable error) {
        if (error == null) return "Неизвестная ошибка";
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("ГУЧИКРАФТ Builder v4");
        alert.setHeaderText("Не удалось выполнить действие");
        alert.setContentText(message == null || message.isBlank() ? "Неизвестная ошибка" : message);
        alert.showAndWait();
    }

    public static void main(String[] args) { launch(args); }
    private RuntimeSettings readRuntimeSettings() {
        int javaVersion = positiveInt(javaVersionField.getText(), "Версия Java");
        int minimum = positiveInt(minimumMemoryField.getText(), "Минимальная память");
        int defaults = positiveInt(defaultMemoryField.getText(), "Память по умолчанию");
        int maximum = positiveInt(maximumMemoryField.getText(), "Максимальная память");
        if (minimum > defaults) {
            throw new IllegalArgumentException("Минимальная память не может быть больше памяти по умолчанию.");
        }
        if (defaults > maximum) {
            throw new IllegalArgumentException("Память по умолчанию не может быть больше максимальной памяти.");
        }
        if (minimum < 1024) {
            throw new IllegalArgumentException("Минимальная память должна быть не меньше 1024 МБ.");
        }
        return new RuntimeSettings(javaVersion, minimum, defaults, maximum);
    }

    private static int positiveInt(String value, String fieldName) {
        try {
            int result = Integer.parseInt(value.trim());
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException(fieldName + " должна быть положительным целым числом.");
        }
    }

    private record RuntimeSettings(int javaVersion, int minimumMemoryMb, int defaultMemoryMb, int maximumMemoryMb) {}

}
