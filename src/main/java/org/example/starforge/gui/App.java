package org.example.starforge.gui;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.example.starforge.core.gen.CompanionIndex;
import org.example.starforge.core.gen.SystemGenerator;
import org.example.starforge.core.io.MySqlPerSystemTableExporter;
import org.example.starforge.core.io.StarCsvReader;
import org.example.starforge.core.model.StarRecord;
import org.example.starforge.core.model.SystemModel;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class App extends Application {

    // Сколько звезд показываем в списке (и с ними работаем в GUI)
    private static final int READ_LIMIT = 50000; // это и есть “50k из 120k”
    private static final long GLOBAL_SEED = 123456789L;

    @Override
    public void start(Stage stage) throws Exception {
        Path csv = Path.of("stars.csv");

        // 1) читаем весь файл
        List<StarRecord> starsAll = new StarCsvReader().read(csv, Integer.MAX_VALUE);

        // 2) фильтруем Солнце и dist=0 (на всякий случай), + любые NaN/мусор
        starsAll = starsAll.stream()
                .filter(s -> s != null)
                .filter(s -> Double.isFinite(s.distPc()) && s.distPc() > 0.0)
                .filter(s -> s.proper() == null || !s.proper().equalsIgnoreCase("Sol"))
                .collect(Collectors.toList());

        // 3) сортировка по дистанции (и стабильно по id)
        starsAll.sort(
                Comparator.comparingDouble(StarRecord::distPc)
                        .thenComparingLong(StarRecord::id)
        );

        // 4) берем первые READ_LIMIT для показа/экспорта
        List<StarRecord> stars = starsAll.subList(0, Math.min(READ_LIMIT, starsAll.size()));

        CompanionIndex companionIndex = CompanionIndex.build(starsAll);
        SystemGenerator gen = new SystemGenerator(GLOBAL_SEED, companionIndex);

        // ===== UI =====
        ListView<StarRecord> list = new ListView<>();
        list.getItems().addAll(stars);
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(StarRecord item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String name = (item.proper() == null || item.proper().isBlank()) ? item.spect() : item.proper();
                    setText(item.id() + "  " + name + "  dist=" + String.format("%.3f", item.distPc()) + " pc");
                }
            }
        });

        OrbitCanvas canvas = new OrbitCanvas();
        TextArea info = new TextArea();
        info.setEditable(false);

        Label header = new Label(
                "Stars shown: " + stars.size() +
                        " / " + starsAll.size() +
                        " (sorted by dist, Sun skipped)"
        );

        // Export controls
        Button exportBtn = new Button("Export " + stars.size() + " systems → MySQL");
        Button correctionsBtn = new Button("Corrections Mode");
        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);

        Label exportStatus = new Label("");
        exportStatus.setWrapText(true);

        exportBtn.setOnAction(ev -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Export to MySQL");
            confirm.setHeaderText("Export " + stars.size() + " star systems to MySQL?");
            confirm.setContentText(
                    "This will write into table StarSystems.\n" +
                            "For each star, rows with ObjectType IN (1,2,3)\n" +
                            "for that StarSystemID will be deleted and re-inserted.\n" +
                            "Rotation speed and rotation inclination are updated as part of this export.\n\n" +
                            "DB connection uses env/system props:\n" +
                            "STARFORGE_DB_URL, STARFORGE_DB_USER, STARFORGE_DB_PASS"
            );

            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

            exportBtn.setDisable(true);
            correctionsBtn.setDisable(true);
            list.setDisable(true);
            progress.setVisible(true);
            progress.setProgress(0);
            exportStatus.setText("Starting export...");

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    MySqlPerSystemTableExporter exporter = new MySqlPerSystemTableExporter();
                    try (Connection c = exporter.openConnection()) {

                        // Reasonable safety for long run:
                        // each system export runs in its own tx inside exportOne.
                        for (int i = 0; i < stars.size(); i++) {
                            if (isCancelled()) break;

                            StarRecord sr = stars.get(i);

                            // Deterministic per-system seed EXACTLY like generator uses (GLOBAL_SEED + star.id)
                            long systemSeed = MySqlPerSystemTableExporter.mixSeed(GLOBAL_SEED, sr.id());

                            // StarSystemID is deterministic by index: 2..(N+1)
                            int starSystemId = i + 2;

                            // Generate system
                            SystemModel sys = gen.generate(sr);

                            // Export
                            exporter.exportOne(c, starSystemId, systemSeed, sr, sys);

                            updateProgress(i + 1, stars.size());
                            updateMessage("Exported " + (i + 1) + " / " + stars.size() + " → StarSystemID=" + starSystemId);
                        }
                    }
                    return null;
                }
            };

            progress.progressProperty().bind(task.progressProperty());
            exportStatus.textProperty().bind(task.messageProperty());

            task.setOnSucceeded(e2 -> {
                exportStatus.textProperty().unbind();
                exportStatus.setText("Export completed.");
                progress.progressProperty().unbind();
                exportBtn.setDisable(false);
                correctionsBtn.setDisable(false);
                list.setDisable(false);
            });

            task.setOnFailed(e2 -> {
                exportStatus.textProperty().unbind();
                Throwable ex = task.getException();
                exportStatus.setText("Export failed: " + (ex != null ? ex.getMessage() : "unknown error"));
                progress.progressProperty().unbind();
                exportBtn.setDisable(false);
                correctionsBtn.setDisable(false);
                list.setDisable(false);
            });

            task.setOnCancelled(e2 -> {
                exportStatus.textProperty().unbind();
                exportStatus.setText("Export cancelled.");
                progress.progressProperty().unbind();
                exportBtn.setDisable(false);
                correctionsBtn.setDisable(false);
                list.setDisable(false);
            });

            Thread th = new Thread(task, "mysql-export");
            th.setDaemon(true);
            th.start();
        });

        correctionsBtn.setOnAction(ev -> {
            ChoiceDialog<String> choose = new ChoiceDialog<>(
                    "Коррекция близких лун",
                    List.of("Коррекция близких лун")
            );
            choose.setTitle("Corrections Mode");
            choose.setHeaderText("Выберите коррекцию");
            choose.setContentText("Коррекция:");
            String selected = choose.showAndWait().orElse(null);
            if (selected == null) return;

            if (!"Коррекция близких лун".equals(selected)) return;

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Run correction");
            confirm.setHeaderText("Запустить \"Коррекция близких лун\"?");
            confirm.setContentText(
                    "Будут проверены все системы в таблице StarSystems.\n" +
                            "Если ближайшая к планете луна внутри жидкого предела Роша,\n" +
                            "она будет заменена на кольцо (ObjectType=9, имя M→R).\n\n" +
                            "Орбитальные параметры сохраняются."
            );
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

            exportBtn.setDisable(true);
            correctionsBtn.setDisable(true);
            list.setDisable(true);
            progress.setVisible(true);
            progress.setProgress(0);
            exportStatus.setText("Starting close-moon correction...");

            Task<String> task = new Task<>() {
                @Override
                protected String call() throws Exception {
                    MySqlPerSystemTableExporter exporter = new MySqlPerSystemTableExporter();
                    int planetsChecked = 0;
                    int converted = 0;
                    try (Connection c = exporter.openConnection()) {
                        List<Integer> systemIds = exporter.listStarSystemIds(c);
                        int total = Math.max(1, systemIds.size());
                        for (int i = 0; i < systemIds.size(); i++) {
                            if (isCancelled()) break;
                            int starSystemId = systemIds.get(i);
                            var stats = exporter.applyCloseMoonCorrection(c, starSystemId);
                            planetsChecked += stats.planetsWithMoons();
                            converted += stats.convertedToRings();
                            updateProgress(i + 1, total);
                            updateMessage(
                                    "Checked " + (i + 1) + " / " + systemIds.size() + " systems"
                                            + " | planets with moons: " + planetsChecked
                                            + " | converted: " + converted
                            );
                        }
                    }
                    return "Correction completed. Planets with moons checked: " + planetsChecked
                            + ", moons converted to rings: " + converted;
                }
            };

            progress.progressProperty().bind(task.progressProperty());
            exportStatus.textProperty().bind(task.messageProperty());

            task.setOnSucceeded(e2 -> {
                exportStatus.textProperty().unbind();
                exportStatus.setText(task.getValue());
                progress.progressProperty().unbind();
                exportBtn.setDisable(false);
                correctionsBtn.setDisable(false);
                list.setDisable(false);
            });

            task.setOnFailed(e2 -> {
                exportStatus.textProperty().unbind();
                Throwable ex = task.getException();
                exportStatus.setText("Correction failed: " + (ex != null ? ex.getMessage() : "unknown error"));
                progress.progressProperty().unbind();
                exportBtn.setDisable(false);
                correctionsBtn.setDisable(false);
                list.setDisable(false);
            });

            task.setOnCancelled(e2 -> {
                exportStatus.textProperty().unbind();
                exportStatus.setText("Correction cancelled.");
                progress.progressProperty().unbind();
                exportBtn.setDisable(false);
                correctionsBtn.setDisable(false);
                list.setDisable(false);
            });

            Thread th = new Thread(task, "mysql-correction-close-moon");
            th.setDaemon(true);
            th.start();
        });

        list.getSelectionModel().selectedItemProperty().addListener((obs, oldV, star) -> {
            if (star == null) return;
            SystemModel sys = gen.generate(star);
            canvas.setSystem(sys);
            info.setText(SystemText.format(sys));
        });

        VBox left = new VBox(8,
                header,
                exportBtn,
                correctionsBtn,
                progress,
                exportStatus,
                list
        );
        VBox.setVgrow(list, Priority.ALWAYS);

        // Справа: сверху канвас, снизу инфо
        VBox systemBox = new VBox(6, new Label("Orbits"), canvas);
        VBox.setVgrow(canvas, Priority.ALWAYS);

        VBox infoBox = new VBox(6, new Label("Info"), info);
        info.setPrefRowCount(10);
        info.setPrefHeight(240);

        VBox right = new VBox(10, systemBox, infoBox);
        VBox.setVgrow(systemBox, Priority.ALWAYS);

        SplitPane root = new SplitPane(left, right);
        root.setDividerPositions(0.28);

        stage.setScene(new Scene(root, 1200, 720));
        stage.setTitle("StarForge Preview");
        stage.show();

        if (!stars.isEmpty()) list.getSelectionModel().select(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
