package ru.ezcraft.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import ru.ezcraft.launcher.auth.OfflineAccount;
import ru.ezcraft.launcher.config.LauncherConfig;
import ru.guchicraft.common.manifest.ClientManifest;
import ru.ezcraft.launcher.service.GameProcessService;
import ru.ezcraft.launcher.service.MinecraftInstallationService;
import ru.ezcraft.launcher.service.MinecraftServerStatusService;
import ru.ezcraft.launcher.service.SettingsService;
import ru.ezcraft.launcher.service.RuntimeManager;
import ru.ezcraft.launcher.service.UpdateService;
import ru.ezcraft.launcher.update.LauncherUpdateManifest;
import ru.ezcraft.launcher.update.LauncherUpdateService;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LauncherApplication extends Application {
    private final LauncherConfig config = LauncherConfig.defaults();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "launcher-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService statusWorker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "server-status-worker");
        thread.setDaemon(true);
        return thread;
    });


    private Path launcherRoot;
    private Path gameDirectory;
    private SettingsService settingsService;
    private volatile ClientManifest remoteManifest;
    private Label versionLabel;
    private Stage primaryStage;
    private volatile LauncherUpdateManifest launcherUpdate;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        launcherRoot = resolveLauncherRoot();
        gameDirectory = launcherRoot.resolve("game");
        settingsService = new SettingsService(launcherRoot.resolve("launcher.properties"));

        TextField nicknameField = new TextField(settingsService.getNickname());
        nicknameField.setPromptText("Введите ник");
        nicknameField.getStyleClass().add("nickname-field");

        Slider memorySlider = new Slider(2048, 16384, settingsService.getMemoryMb());
        memorySlider.setBlockIncrement(512);
        memorySlider.setMajorTickUnit(2048);
        memorySlider.setMinorTickCount(3);
        memorySlider.setSnapToTicks(true);

        Label memoryValue = new Label(formatMemory((int) memorySlider.getValue()));
        memoryValue.getStyleClass().add("memory-value");
        memorySlider.valueProperty().addListener((observable, oldValue, newValue) ->
                memoryValue.setText(formatMemory(newValue.intValue())));

        Label statusLabel = new Label("Лаунчер готов к работе");
        statusLabel.getStyleClass().add("status-text");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.getStyleClass().add("update-progress");

        Button playButton = new Button("ИГРАТЬ");
        playButton.getStyleClass().add("play-button");
        playButton.setMaxWidth(Double.MAX_VALUE);

        CheckBox hideAfterStart = new CheckBox("Скрывать лаунчер во время игры");
        hideAfterStart.setSelected(settingsService.isHideAfterStart());
        hideAfterStart.getStyleClass().add("launcher-checkbox");

        Button openFolderButton = new Button("Открыть папку игры");
        openFolderButton.getStyleClass().add("secondary-button");
        openFolderButton.setMaxWidth(Double.MAX_VALUE);
        openFolderButton.setOnAction(event -> openGameDirectory());

        Button updateButton = new Button("Проверка обновления лаунчера…");
        updateButton.getStyleClass().add("secondary-button");
        updateButton.setMaxWidth(Double.MAX_VALUE);
        updateButton.setDisable(true);
        updateButton.setOnAction(event -> installLauncherUpdate(updateButton, statusLabel, progressBar));

        playButton.setOnAction(event -> startGame(
                nicknameField,
                memorySlider,
                statusLabel,
                progressBar,
                playButton,
                hideAfterStart
        ));

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(createHeader());
        root.setCenter(createMainContent(
                nicknameField,
                memorySlider,
                memoryValue,
                statusLabel,
                progressBar,
                playButton,
                hideAfterStart,
                openFolderButton,
                updateButton
        ));

        Scene scene = new Scene(root, 1000, 640);
        scene.getStylesheets().add(
                LauncherApplication.class.getResource("/launcher.css").toExternalForm()
        );

        stage.setTitle(config.launcherName());
        stage.setMinWidth(880);
        stage.setMinHeight(580);
        stage.setScene(scene);
        stage.show();

        loadRemoteConfiguration(memorySlider, memoryValue, statusLabel);
        checkLauncherUpdate(updateButton, statusLabel);
    }

    private HBox createHeader() {
        ImageView logoMark = createLogo();

        Label title = new Label(config.launcherName());
        title.getStyleClass().add("launcher-title");

        versionLabel = new Label("Получение конфигурации клиента…");
        versionLabel.getStyleClass().add("launcher-version");

        VBox branding = new VBox(2, title, versionLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label onlineDot = new Label("●");
        onlineDot.getStyleClass().add("offline-dot");
        Label serverText = new Label("Проверка сервера…");
        serverText.getStyleClass().add("server-address");
        Label playersText = new Label("");
        playersText.getStyleClass().add("server-players");
        VBox serverInfo = new VBox(2, serverText, playersText);
        serverInfo.setAlignment(Pos.CENTER_RIGHT);
        HBox server = new HBox(8, onlineDot, serverInfo);
        server.setAlignment(Pos.CENTER_RIGHT);
        startServerStatusUpdates(onlineDot, serverText, playersText);

        HBox header = new HBox(14, logoMark, branding, spacer, server);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(22, 28, 18, 28));
        header.getStyleClass().add("header");
        return header;
    }

    private StackPane createMainContent(
            TextField nicknameField,
            Slider memorySlider,
            Label memoryValue,
            Label statusLabel,
            ProgressBar progressBar,
            Button playButton,
            CheckBox hideAfterStart,
            Button openFolderButton,
            Button updateButton
    ) {
        VBox heroText = new VBox(10);
        heroText.setMaxWidth(500);

        Label eyebrow = new Label("ДОБРО ПОЖАЛОВАТЬ");
        eyebrow.getStyleClass().add("eyebrow");

        Label headline = new Label("Твой мир.\nТвои правила.");
        headline.getStyleClass().add("headline");

        Label description = new Label(
                "Лаунчер самостоятельно проверит файлы сборки, обновит моды и запустит клиент для подключения к серверу."
        );
        description.setWrapText(true);
        description.getStyleClass().add("description");

        heroText.getChildren().addAll(eyebrow, headline, description);

        VBox card = new VBox(16);
        card.setMaxWidth(390);
        card.getStyleClass().add("launcher-card");

        Label cardTitle = new Label("Запуск игры");
        cardTitle.getStyleClass().add("card-title");

        Label nicknameLabel = new Label("Ник игрока");
        nicknameLabel.getStyleClass().add("field-label");

        HBox memoryHeader = new HBox();
        Label memoryLabel = new Label("Оперативная память");
        memoryLabel.getStyleClass().add("field-label");
        Region memorySpacer = new Region();
        HBox.setHgrow(memorySpacer, Priority.ALWAYS);
        memoryHeader.getChildren().addAll(memoryLabel, memorySpacer, memoryValue);

        VBox progressArea = new VBox(8, progressBar, statusLabel);
        progressArea.getStyleClass().add("progress-area");

        card.getChildren().addAll(
                cardTitle,
                nicknameLabel,
                nicknameField,
                memoryHeader,
                memorySlider,
                progressArea,
                hideAfterStart,
                playButton,
                openFolderButton,
                updateButton
        );

        Region middleSpacer = new Region();
        HBox.setHgrow(middleSpacer, Priority.ALWAYS);

        HBox content = new HBox(52, heroText, middleSpacer, card);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(34, 52, 48, 52));

        StackPane pane = new StackPane(content);
        pane.getStyleClass().add("main-content");
        return pane;
    }

    private ImageView createLogo() {
        Image image = new Image(
                LauncherApplication.class.getResourceAsStream("/images/logo.png"),
                54,
                54,
                true,
                true
        );
        ImageView view = new ImageView(image);
        view.setFitWidth(54);
        view.setFitHeight(54);
        view.setPreserveRatio(true);
        view.getStyleClass().add("logo-image");
        return view;
    }

    private void startServerStatusUpdates(Label dot, Label serverText, Label playersText) {
        MinecraftServerStatusService service = new MinecraftServerStatusService();
        statusWorker.scheduleWithFixedDelay(() -> {
            ClientManifest currentManifest = remoteManifest;
            String address = currentManifest != null && currentManifest.serverAddress() != null
                    && !currentManifest.serverAddress().isBlank()
                    ? currentManifest.serverAddress()
                    : config.serverAddress();
            var status = service.query(address);
            Platform.runLater(() -> {
                dot.getStyleClass().removeAll("online-dot", "offline-dot");
                if (status.online()) {
                    dot.getStyleClass().add("online-dot");
                    serverText.setText(address);
                    playersText.setText(status.onlinePlayers() + " / " + status.maxPlayers() + " игроков  •  " + status.latencyMs() + " мс");
                } else {
                    dot.getStyleClass().add("offline-dot");
                    serverText.setText(address);
                    playersText.setText("Сервер недоступен");
                }
            });
        }, 0, 30, TimeUnit.SECONDS);
    }

    private void startGame(
            TextField nicknameField,
            Slider memorySlider,
            Label statusLabel,
            ProgressBar progressBar,
            Button playButton,
            CheckBox hideAfterStart
    ) {
        String nickname = nicknameField.getText().trim();
        if (!nickname.matches("[A-Za-z0-9_]{3,16}")) {
            showError("Некорректный ник", "Ник должен содержать от 3 до 16 латинских букв, цифр или символов подчёркивания.");
            return;
        }

        int memoryMb = (int) memorySlider.getValue();
        settingsService.save(nickname, memoryMb, hideAfterStart.isSelected());
        playButton.setDisable(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        statusLabel.setText("Подготовка запуска…");

        worker.submit(() -> {
            try {
                OfflineAccount account = OfflineAccount.of(nickname);
                Files.createDirectories(gameDirectory);

                UpdateService updater = new UpdateService();
                setStatus(statusLabel, "Получение manifest.json…");
                var loadedManifest = updater.load(config.manifestUrl());
                ClientManifest manifest = loadedManifest.manifest();
                validateManifest(manifest);
                remoteManifest = manifest;

                int effectiveMemory = clampMemory(memoryMb, manifest.launch());
                if (effectiveMemory != memoryMb) {
                    settingsService.save(nickname, effectiveMemory, hideAfterStart.isSelected());
                    Platform.runLater(() -> memorySlider.setValue(effectiveMemory));
                }

                setStatus(statusLabel, "Проверка сборки " + safeBuildVersion(manifest.buildVersion()) + "…");
                var syncResult = updater.sync(
                        loadedManifest,
                        gameDirectory,
                        progress -> Platform.runLater(() -> {
                            progressBar.setProgress(progress.fraction() * 0.35);
                            statusLabel.setText(formatUpdateStatus(progress));
                        })
                );

                setStatus(statusLabel, "Установка Minecraft " + manifest.minecraftVersion() + " и Fabric "
                        + manifest.fabricLoaderVersion() + "…");
                MinecraftInstallationService installationService = new MinecraftInstallationService();
                var installation = installationService.install(
                        manifest.minecraftVersion(),
                        manifest.fabricLoaderVersion(),
                        gameDirectory,
                        text -> setStatus(statusLabel, text),
                        fraction -> Platform.runLater(() -> progressBar.setProgress(0.35 + fraction * 0.45))
                );

                int requiredJava = Math.max(manifest.java().majorVersion(), installation.requiredJavaMajor());
                setStatus(statusLabel, "Проверка Java " + requiredJava + "…");
                RuntimeManager runtimeManager = new RuntimeManager();
                Path javaExecutable = runtimeManager.ensureRuntime(
                        requiredJava,
                        launcherRoot,
                        text -> setStatus(statusLabel, text),
                        fraction -> Platform.runLater(() -> progressBar.setProgress(0.80 + fraction * 0.15))
                );
                String serverAddress = manifest.serverAddress() == null || manifest.serverAddress().isBlank()
                        ? config.serverAddress()
                        : manifest.serverAddress().trim();

                setStatus(statusLabel, "Запуск Minecraft…");
                Process gameProcess = new GameProcessService().launch(
                        gameDirectory,
                        javaExecutable,
                        effectiveMemory,
                        account,
                        serverAddress,
                        installation,
                        line -> { }
                );

                Platform.runLater(() -> {
                    progressBar.setProgress(1);
                    statusLabel.setText("Minecraft запущен • сборка " + safeBuildVersion(manifest.buildVersion()));
                    versionLabel.setText("Minecraft " + manifest.minecraftVersion() + "  •  Fabric " + manifest.fabricLoaderVersion());
                    if (hideAfterStart.isSelected()) primaryStage.hide();
                });

                Thread gameWatcher = new Thread(() -> {
                    try {
                        int exitCode = gameProcess.waitFor();
                        Platform.runLater(() -> {
                            if (hideAfterStart.isSelected()) primaryStage.show();
                            progressBar.setProgress(0);
                            statusLabel.setText(exitCode == 0
                                    ? "Игра завершена"
                                    : "Игра завершилась с кодом " + exitCode + ". Проверь logs/launcher-game.log");
                        });
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }, "minecraft-process-watcher");
                gameWatcher.setDaemon(true);
                gameWatcher.start();
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    statusLabel.setText("Не удалось запустить игру");
                    showError("Ошибка запуска", readableMessage(exception));
                });
            } finally {
                Platform.runLater(() -> playButton.setDisable(false));
            }
        });
    }

    private void loadRemoteConfiguration(Slider memorySlider, Label memoryValue, Label statusLabel) {
        worker.submit(() -> {
            try {
                ClientManifest manifest = new UpdateService().load(config.manifestUrl()).manifest();
                validateManifest(manifest);
                remoteManifest = manifest;
                Platform.runLater(() -> {
                    var launch = manifest.launch();
                    memorySlider.setMin(launch.minimumMemoryMb());
                    memorySlider.setMax(launch.maximumMemoryMb());
                    int saved = settingsService.getMemoryMb();
                    int selected = saved < launch.minimumMemoryMb() || saved > launch.maximumMemoryMb()
                            ? launch.defaultMemoryMb() : saved;
                    memorySlider.setValue(selected);
                    memoryValue.setText(formatMemory(selected));
                    versionLabel.setText("Minecraft " + manifest.minecraftVersion() + "  •  Fabric " + manifest.fabricLoaderVersion());
                    statusLabel.setText("Готова сборка " + safeBuildVersion(manifest.buildVersion()));
                });
            } catch (Exception exception) {
                Platform.runLater(() -> statusLabel.setText("Не удалось получить конфигурацию: " + readableMessage(exception)));
            }
        });
    }

    private void validateManifest(ClientManifest manifest) {
        if (manifest.manifestVersion() < 2) {
            throw new IllegalStateException("Нужен manifestVersion 2. Пересобери manifest через Builder v4.");
        }
        if (manifest.minecraftVersion() == null || manifest.minecraftVersion().isBlank()) {
            throw new IllegalStateException("В manifest не указана версия Minecraft");
        }
        if (manifest.fabricLoaderVersion() == null || manifest.fabricLoaderVersion().isBlank()) {
            throw new IllegalStateException("В manifest не указана версия Fabric Loader");
        }
    }

    private int clampMemory(int value, ClientManifest.LaunchConfiguration launch) {
        return Math.max(launch.minimumMemoryMb(), Math.min(value, launch.maximumMemoryMb()));
    }

    private String formatUpdateStatus(UpdateService.Progress progress) {
        return switch (progress.phase()) {
            case CHECKING -> "Проверка " + progress.fileIndex() + " / " + progress.fileCount() + ": " + progress.file();
            case DOWNLOADING -> "Скачивание " + progress.fileIndex() + " / " + progress.fileCount()
                    + ": " + progress.file() + "  •  " + formatBytes(progress.completedBytes())
                    + " / " + formatBytes(progress.totalBytes()) + "  •  " + formatBytes(progress.bytesPerSecond()) + "/с";
            case REMOVING -> "Удаление старого файла: " + progress.file();
            case COMPLETE -> "Проверка файлов завершена";
        };
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f КБ", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f МБ", mb);
        return String.format(java.util.Locale.ROOT, "%.2f ГБ", mb / 1024.0);
    }

    private String safeBuildVersion(String version) {
        return version == null || version.isBlank() ? "без номера" : version;
    }

    private Path resolveLauncherRoot() {
        String appData = System.getenv("APPDATA");
        Path base = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".ezcraft")
                : Path.of(appData, config.dataDirectory());
        return base.toAbsolutePath().normalize();
    }


    private void checkLauncherUpdate(Button updateButton, Label statusLabel) {
        worker.submit(() -> {
            try {
                LauncherUpdateService service = new LauncherUpdateService();
                LauncherUpdateManifest update = service.load(config.updateManifestUrl());
                if (!service.isNewer(update.version(), config.launcherVersion())) {
                    Platform.runLater(() -> {
                        updateButton.setText("Лаунчер обновлён • v" + config.launcherVersion());
                        updateButton.setDisable(true);
                    });
                    return;
                }
                launcherUpdate = update;
                Platform.runLater(() -> {
                    updateButton.setText("Обновить лаунчер до v" + update.version());
                    updateButton.setDisable(false);
                    if (update.mandatory()) {
                        statusLabel.setText("Требуется обновление лаунчера до v" + update.version());
                    }
                });
            } catch (LauncherUpdateService.UpdateNotConfiguredException notConfigured) {
                Platform.runLater(() -> {
                    updateButton.setText("Обновления пока не опубликованы");
                    updateButton.setDisable(true);
                });
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    updateButton.setText("Не удалось проверить обновление");
                    updateButton.setDisable(false);
                    updateButton.setOnAction(event -> checkLauncherUpdate(updateButton, statusLabel));
                });
            }
        });
    }

    private void installLauncherUpdate(Button updateButton, Label statusLabel, ProgressBar progressBar) {
        LauncherUpdateManifest update = launcherUpdate;
        if (update == null) {
            checkLauncherUpdate(updateButton, statusLabel);
            return;
        }
        updateButton.setDisable(true);
        progressBar.setProgress(0);
        statusLabel.setText("Скачивание обновления лаунчера v" + update.version() + "…");
        worker.submit(() -> {
            try {
                LauncherUpdateService service = new LauncherUpdateService();
                Path zip = service.download(update, launcherRoot.resolve("downloads"), (completed, total) ->
                        Platform.runLater(() -> {
                            progressBar.setProgress(total > 0 ? (double) completed / total : ProgressBar.INDETERMINATE_PROGRESS);
                            statusLabel.setText("Обновление лаунчера: " + formatBytes(completed)
                                    + (total > 0 ? " / " + formatBytes(total) : ""));
                        }));

                if (!service.isPackagedApplication()) {
                    Platform.runLater(() -> {
                        progressBar.setProgress(1);
                        statusLabel.setText("Обновление скачано и проверено");
                        showInfo("Обновление готово",
                                "Проверка загрузки и SHA-256 прошла успешно.\n\n"
                                        + "Сейчас лаунчер запущен из IntelliJ, поэтому заменить его автоматически нельзя. "
                                        + "Автозамена заработает после сборки приложения .exe.\n\nФайл: " + zip);
                        updateButton.setDisable(false);
                    });
                    return;
                }

                Path executable = Path.of(ProcessHandle.current().info().command().orElseThrow())
                        .toAbsolutePath().normalize();
                Path appDirectory = executable.getParent();
                Path updaterJar = service.resolveUpdaterJar(appDirectory);
                Path updaterJava = service.resolveUpdaterJava(launcherRoot, appDirectory);
                service.scheduleExternalUpdater(
                        updaterJava,
                        updaterJar,
                        zip,
                        appDirectory,
                        executable,
                        launcherRoot.resolve("updater")
                );
                Platform.runLater(() -> {
                    statusLabel.setText("Перезапуск для установки обновления…");
                    Platform.exit();
                });
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    statusLabel.setText("Не удалось обновить лаунчер");
                    updateButton.setDisable(false);
                    showError("Ошибка обновления", readableMessage(exception));
                });
            }
        });
    }

    private void openGameDirectory() {
        try {
            Files.createDirectories(gameDirectory);
            if (!Desktop.isDesktopSupported()) {
                throw new IllegalStateException("Открытие папок не поддерживается в этой системе");
            }
            Desktop.getDesktop().open(gameDirectory.toFile());
        } catch (Exception exception) {
            showError("Не удалось открыть папку", readableMessage(exception));
        }
    }

    private void setStatus(Label statusLabel, String text) {
        Platform.runLater(() -> statusLabel.setText(text));
    }

    private String formatMemory(int memoryMb) {
        return memoryMb >= 1024 && memoryMb % 1024 == 0
                ? (memoryMb / 1024) + " ГБ"
                : memoryMb + " МБ";
    }

    private String readableMessage(Exception exception) {
        Throwable current = exception;
        String best = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                best = current.getMessage();
            }
            current = current.getCause();
        }
        return best == null || best.isBlank() ? exception.getClass().getSimpleName() : best;
    }

    private void showInfo(String title, String message) {
        new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK) {{
            setTitle(title);
            setHeaderText(null);
        }}.showAndWait();
    }

    private void showError(String title, String message) {
        new Alert(Alert.AlertType.ERROR, message, ButtonType.OK) {{
            setTitle(title);
            setHeaderText(null);
        }}.showAndWait();
    }

    @Override
    public void stop() {
        worker.shutdownNow();
        statusWorker.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
