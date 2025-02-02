package lanradar;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lanradar.NetworkDevice.DeviceStatus;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * JavaFX-приложение для периодического сканирования сети.
 */
public class NetworkScannerApp extends Application {

    private static final Logger logger = LoggerFactory.getLogger(NetworkScannerApp.class);
    private TableView<NetworkDevice> tableView;
    private ObservableList<NetworkDevice> deviceData = FXCollections.observableArrayList();
    private TextField manualSubnetField;
    private ComboBox<String> subnetComboBox;
    private Button scanButton;
    private ScheduledExecutorService scheduler;
    private Future<?> fullScanFuture;
    private Future<?> partialScanFuture;
    private final Map<String, NetworkDevice> knownDevices = new ConcurrentHashMap<>();
    private static final int FULL_SCAN_PERIOD = 30;
    private static final int PARTIAL_SCAN_PERIOD = 5;
    private boolean scanning = false;
    private boolean firstScan = true;

    /**
     * Основной метод запуска JavaFX-приложения.
     *
     * @param primaryStage Главное окно приложения.
     */
    @Override
    public void start(Stage primaryStage) {
        Pane overlayPane = new Pane();
        Image backgroundImage = new Image("/fon_minimal.png");
        BackgroundImage bg = new BackgroundImage(
                backgroundImage,
                BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.DEFAULT,
                new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO, false, false, true, true)
        );
        overlayPane.setBackground(new Background(bg));

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.prefWidthProperty().bind(overlayPane.widthProperty());
        root.prefHeightProperty().bind(overlayPane.heightProperty());
        overlayPane.getChildren().add(root);

        subnetComboBox = new ComboBox<>();
        subnetComboBox.setPromptText("Выберите подсеть");
        subnetComboBox.getItems().addAll(UtilityNetwork.listAdapterSubnets());
        subnetComboBox.setPrefHeight(30);
        subnetComboBox.setPrefWidth(150);
        subnetComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty()) {
                manualSubnetField.clear();
            }
        });
        subnetComboBox.setOnShowing(e -> {
            List<String> currentItems = subnetComboBox.getItems();
            List<String> newSubnets = UtilityNetwork.listAdapterSubnets();
            if (!currentItems.equals(newSubnets)) {
                subnetComboBox.getItems().setAll(newSubnets);
            }
        });

        manualSubnetField = new TextField();
        manualSubnetField.setPromptText("или введите свою");
        manualSubnetField.setPrefHeight(30);
        manualSubnetField.setPrefWidth(150);
        manualSubnetField.getStyleClass().add("combo-box");
        manualSubnetField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal.isEmpty()) {
                subnetComboBox.setValue("");
            }
        });

        scanButton = new Button("Сканировать");
        scanButton.setPrefHeight(30);
        scanButton.setOnAction(e -> onScanButtonClicked());

        HBox leftControls = new HBox(10, subnetComboBox, manualSubnetField, scanButton);
        leftControls.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Image doraImage = new Image("/doraLogo.png", 24, 24, true, true);
        ImageView aboutIcon = new ImageView(doraImage);
        aboutIcon.setOpacity(0.10);
        aboutIcon.setOnMouseEntered(e -> aboutIcon.setOpacity(1.0));
        aboutIcon.setOnMouseExited(e -> aboutIcon.setOpacity(0.10));
        aboutIcon.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                showAboutWindow();
            }
        });

        HBox controls = new HBox(10, leftControls, spacer, aboutIcon);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(0, 0, 10, 0));

        tableView = new TableView<>();
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setItems(deviceData);
        tableView.setPlaceholder(new Label("Нет данных для отображения"));

        TableColumn<NetworkDevice, String> ipColumn = new TableColumn<>("IP-адрес");
        ipColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getIpAddress()));
        ipColumn.setComparator(this::compareIPs);
        ipColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                setStyle("-fx-text-fill: black;");
                setText(empty || val == null ? null : val);
            }
        });

        TableColumn<NetworkDevice, String> macColumn = new TableColumn<>("MAC-адрес");
        macColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getMacAddress()));
        macColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                setStyle("-fx-text-fill: black;");
                setText(empty || val == null ? null : val);
            }
        });

        TableColumn<NetworkDevice, String> manufacturerColumn = new TableColumn<>("Производитель");
        manufacturerColumn.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getManufacturerName() != null ? cd.getValue().getManufacturerName() : ""));
        manufacturerColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                setStyle("-fx-text-fill: black;");
                setText(empty || val == null ? null : val);
            }
        });

        TableColumn<NetworkDevice, String> dnsColumn = new TableColumn<>("DNS-имя");
        dnsColumn.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getDNSname() != null ? cd.getValue().getDNSname() : ""));
        dnsColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                setStyle("-fx-text-fill: black;");
                setText(empty || val == null ? null : val);
            }
        });

        TableColumn<NetworkDevice, NetworkDevice> snmpColumn = new TableColumn<>("SNMP");
        snmpColumn.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
        snmpColumn.setCellFactory(col -> new TableCell<>() {
            private final Label walkLabel = new Label("Walk");

            {
                walkLabel.setStyle("-fx-text-fill: blue; -fx-underline: true; -fx-font-size: 9px;");
                walkLabel.setOnMouseEntered(e -> walkLabel.setStyle("-fx-text-fill: darkblue; -fx-underline: true; -fx-font-size: 9px;"));
                walkLabel.setOnMouseExited(e -> walkLabel.setStyle("-fx-text-fill: blue; -fx-underline: true; -fx-font-size: 9px;"));
                walkLabel.setOnMouseClicked(evt -> {
                    if (evt.getButton() == MouseButton.PRIMARY && evt.getClickCount() == 1) {
                        NetworkDevice device = getTableView().getItems().get(getIndex());
                        showSnmpWalkWindow(device.getIpAddress());
                    }
                });
            }

            @Override
            protected void updateItem(NetworkDevice dev, boolean empty) {
                super.updateItem(dev, empty);
                setStyle("-fx-text-fill: black;");
                if (empty || dev == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    if (dev.getSNMPAvailable()) {
                        setGraphic(walkLabel);
                        setText(null);
                    } else {
                        setGraphic(null);
                        setText("Нет");
                    }
                }
            }
        });

        ipColumn.setPrefWidth(120);
        macColumn.setPrefWidth(150);
        manufacturerColumn.setPrefWidth(430);
        dnsColumn.setPrefWidth(200);
        snmpColumn.setPrefWidth(100);

        tableView.getColumns().addAll(ipColumn, macColumn, manufacturerColumn, dnsColumn, snmpColumn);
        tableView.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(NetworkDevice dev, boolean empty) {
                super.updateItem(dev, empty);
                setStyle("-fx-text-fill: black;");
                if (empty || dev == null) {
                    setStyle("");
                } else {
                    switch (dev.getStatus()) {
                        case NEW:
                            setStyle("-fx-background-color: #b3ffb3; -fx-text-fill: black;");
                            break;
                        case CHANGED:
                            setStyle("-fx-background-color: #ffffbf; -fx-text-fill: black;");
                            break;
                        case LOST:
                            setStyle("-fx-background-color: #ffb3b3; -fx-text-fill: black;");
                            break;
                        default:
                            setStyle("-fx-text-fill: black;");
                            break;
                    }
                }
            }
        });
        tableView.setOnKeyPressed(event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                copySelectionToClipboard();
            }
        });

        VBox mainLayout = new VBox(10, controls, tableView);
        VBox.setVgrow(tableView, Priority.ALWAYS);
        root.setCenter(mainLayout);

        Scene scene = new Scene(overlayPane);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        primaryStage.getIcons().add(new Image("/label.png"));
        primaryStage.setWidth(900);
        primaryStage.setHeight(600);
        primaryStage.setTitle("LAN Radar");
        primaryStage.setScene(scene);
        primaryStage.show();

        try {
            SNMP.initSnmp();
        } catch (IOException e) {
            logger.error("Ошибка инициализации SNMP: {}", e.getMessage(), e);
        }
    }

    /**
     * Отображает окно "О программе".
     */
    private void showAboutWindow() {
        Stage aboutStage = new Stage();
        aboutStage.initModality(Modality.APPLICATION_MODAL);
        // Пасхалка: Окно "Привет, это Дора" открывается при клике на doraLogo
        aboutStage.setTitle("Привет, это Дора");
        aboutStage.getIcons().add(new Image("/elogo.png"));

        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));
        vbox.setAlignment(Pos.CENTER);
        Label linkLabel = new Label("https://github.com/LeeRoyod");
        linkLabel.setStyle("-fx-text-fill: blue; -fx-underline: true;");
        linkLabel.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                getHostServices().showDocument("https://github.com/LeeRoyod");
            }
        });
        vbox.getChildren().addAll(linkLabel);
        Scene aboutScene = new Scene(vbox, 300, 100);
        aboutStage.setScene(aboutScene);
        aboutStage.show();
    }

    /**
     * Копирует выделенные ячейки таблицы в буфер обмена.
     */
    private void copySelectionToClipboard() {
        TableView.TableViewSelectionModel<NetworkDevice> selectionModel = tableView.getSelectionModel();
        ObservableList<TablePosition> selectedCells = selectionModel.getSelectedCells();
        if (selectedCells.isEmpty()) {
            return;
        }
        List<TablePosition> sorted = new ArrayList<>(selectedCells);
        sorted.sort(Comparator.comparingInt((TablePosition tp) -> tp.getRow())
                .thenComparingInt(tp -> tp.getColumn()));
        StringBuilder sb = new StringBuilder();
        int prevRow = -1;
        for (TablePosition cellPos : sorted) {
            int row = cellPos.getRow();
            int col = cellPos.getColumn();
            Object cellValue = tableView.getColumns().get(col).getCellData(row);
            if (cellValue == null) cellValue = "";
            if (row == prevRow) {
                sb.append("\t");
            } else if (prevRow != -1) {
                sb.append("\n");
            }
            sb.append(cellValue.toString());
            prevRow = row;
        }
        ClipboardContent clipboardContent = new ClipboardContent();
        clipboardContent.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(clipboardContent);
    }

    /**
     * Обрабатывает нажатие кнопки "Сканировать"/"Остановить". Валидирует ввод и запускает/останавливает сканирование.
     */
    private void onScanButtonClicked() {
        if (!scanning) {
            String manualInput = manualSubnetField.getText();
            if (manualInput != null && !manualInput.trim().isEmpty()) {
                String input = manualInput.trim();
                if (!input.matches("^\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}$")) {
                    showErrorTooltip(manualSubnetField, "Формат должен быть \"ip/маска\", например 192.168.0.99/24");
                    return;
                }
                String[] parts = input.split("/");
                String ipPart = parts[0];
                String[] octets = ipPart.split("\\.");
                for (String octet : octets) {
                    int value;
                    try {
                        value = Integer.parseInt(octet);
                    } catch (NumberFormatException e) {
                        showErrorTooltip(manualSubnetField, "IP-адрес должен состоять из чисел");
                        return;
                    }
                    if (value < 0 || value > 255) {
                        showErrorTooltip(manualSubnetField, "Каждый октет IP-адреса должен быть от 0 до 255");
                        return;
                    }
                }
                int mask;
                try {
                    mask = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    showErrorTooltip(manualSubnetField, "Маска подсети должна быть числом");
                    return;
                }
                if (mask < 0 || mask > 32) {
                    showErrorTooltip(manualSubnetField, "Маска подсети должна быть от 0 до 32");
                    return;
                }
            }
            String finalSubnet = getSelectedSubnet();
            if (finalSubnet == null || finalSubnet.isEmpty()) {
                showErrorTooltip(subnetComboBox, "Не выбрана подсеть в списке");
                showErrorTooltip(manualSubnetField, "или не введена вручную");
                return;
            }
            scanning = true;
            firstScan = true;
            knownDevices.clear();
            deviceData.clear();
            scanButton.setText("Остановить сканирование");
            subnetComboBox.setDisable(true);
            manualSubnetField.setDisable(true);
            startPeriodicScan();
        } else {
            scanning = false;
            scanButton.setText("Сканировать");
            subnetComboBox.setDisable(false);
            manualSubnetField.setDisable(false);
            stopPeriodicScan();
        }
    }

    /**
     * Отображает всплывающую подсказку с сообщением об ошибке.
     *
     * @param control UI-элемент (TextField/ComboBox).
     * @param message Текст ошибки.
     */
    private void showErrorTooltip(Control control, String message) {
        Tooltip tooltip = new Tooltip(message);
        tooltip.setAutoHide(true);
        tooltip.setStyle("-fx-background-color: rgba(255,225,225,0.8); -fx-text-fill: red;");
        double x = control.localToScreen(control.getBoundsInLocal()).getMinX();
        double y = control.localToScreen(control.getBoundsInLocal()).getMinY() - 25;
        tooltip.show(control, x, y);
    }

    /**
     * Запускает периодическое полное и частичное сканирование сети.
     */
    private void startPeriodicScan() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newScheduledThreadPool(2);
        }
        fullScanFuture = scheduler.scheduleWithFixedDelay(() -> {
            try {
                doFullScan();
            } catch (Exception ex) {
                logger.error("Полное сканирование прервано или ошибка: {}", ex.getMessage(), ex);
            }
        }, 0, FULL_SCAN_PERIOD, TimeUnit.SECONDS);
        partialScanFuture = scheduler.scheduleWithFixedDelay(() -> {
            try {
                doPartialScan();
            } catch (Exception ex) {
                logger.error("Частичное сканирование прервано или ошибка: {}", ex.getMessage(), ex);
            }
        }, PARTIAL_SCAN_PERIOD, PARTIAL_SCAN_PERIOD, TimeUnit.SECONDS);
    }

    /**
     * Останавливает периодическое сканирование.
     */
    private void stopPeriodicScan() {
        if (scheduler != null && !scheduler.isShutdown()) {
            if (fullScanFuture != null) {
                fullScanFuture.cancel(true);
            }
            if (partialScanFuture != null) {
                partialScanFuture.cancel(true);
            }
            scheduler.shutdownNow();
        }
    }

    /**
     * Выполняет полное сканирование выбранной подсети.
     */
    private void doFullScan() {
        if (!scanning) return;
        String finalSubnet = getSelectedSubnet();
        if (finalSubnet == null) return;
        List<String> range = UtilityNetwork.calculateHostRange(finalSubnet);
        if (range.size() < 2) return;
        if (!scanning) return;

        List<NetworkDevice> scanned = NetworkScanner.findDevicesInSubnet(range.get(0), range.get(1));
        if (!scanning) return;
        if (scanned == null) return;
        Map<String, NetworkDevice> scannedMap = new HashMap<>();
        for (NetworkDevice dev : scanned) {
            scannedMap.put(dev.getIpAddress(), dev);
        }
        if (!scanning) return;

        for (String oldIP : knownDevices.keySet()) {
            if (!scannedMap.containsKey(oldIP)) {
                NetworkDevice oldDev = knownDevices.get(oldIP);
                if (oldDev.getStatus() != DeviceStatus.LOST) {
                    oldDev.setStatus(DeviceStatus.LOST);
                }
            }
        }
        for (NetworkDevice dev : scanned) {
            String ip = dev.getIpAddress();
            NetworkDevice oldDev = knownDevices.get(ip);
            if (oldDev == null) {
                if (firstScan) {
                    dev.setStatus(DeviceStatus.NORMAL);
                } else {
                    dev.setStatus(DeviceStatus.NEW);
                    dev.setScansAsNew(0);
                }
                knownDevices.put(ip, dev);
            } else {
                if (!Objects.equals(oldDev.getMacAddress(), dev.getMacAddress())) {
                    dev.setStatus(DeviceStatus.CHANGED);
                } else {
                    if (oldDev.getStatus() == DeviceStatus.LOST) {
                        dev.setStatus(DeviceStatus.NEW);
                        dev.setScansAsNew(0);
                    } else {
                        dev.setStatus(oldDev.getStatus());
                        dev.setScansAsNew(oldDev.getScansAsNew());
                    }
                }
                knownDevices.put(ip, dev);
            }
        }
        if (firstScan) {
            firstScan = false;
        } else {
            for (NetworkDevice d : knownDevices.values()) {
                if (d.getStatus() == DeviceStatus.NEW) {
                    d.setScansAsNew(d.getScansAsNew() + 1);
                    if (d.getScansAsNew() >= 2) {
                        d.setStatus(DeviceStatus.NORMAL);
                    }
                }
            }
        }
        if (!scanning) return;
        updateDeviceData();
    }

    /**
     * Выполняет частичное сканирование ранее обнаруженных IP.
     */
    private void doPartialScan() {
        if (!scanning) return;
        if (knownDevices.isEmpty()) return;
        String finalSubnet = getSelectedSubnet();
        if (finalSubnet == null) return;

        List<String> ipList = new ArrayList<>(knownDevices.keySet());
        List<NetworkDevice> scanned = NetworkScanner.findDevicesByIPs(ipList);
        if (!scanning) return;
        if (scanned == null) return;
        Map<String, NetworkDevice> scannedMap = new HashMap<>();
        for (NetworkDevice dev : scanned) {
            scannedMap.put(dev.getIpAddress(), dev);
        }
        if (!scanning) return;
        for (String oldIP : knownDevices.keySet()) {
            if (!scannedMap.containsKey(oldIP)) {
                NetworkDevice oldDev = knownDevices.get(oldIP);
                oldDev.setStatus(DeviceStatus.LOST);
            }
        }
        for (NetworkDevice dev : scanned) {
            String ip = dev.getIpAddress();
            NetworkDevice oldDev = knownDevices.get(ip);
            if (!Objects.equals(oldDev.getMacAddress(), dev.getMacAddress())) {
                dev.setStatus(DeviceStatus.CHANGED);
            } else {
                if (oldDev.getStatus() == DeviceStatus.LOST) {
                    dev.setStatus(DeviceStatus.NEW);
                    dev.setScansAsNew(0);
                } else {
                    dev.setStatus(oldDev.getStatus());
                    dev.setScansAsNew(oldDev.getScansAsNew());
                }
            }
            knownDevices.put(ip, dev);
        }
        if (!scanning) return;
        updateDeviceData();
    }

    /**
     * Обновляет данные в таблице (UI).
     */
    private void updateDeviceData() {
        Platform.runLater(() -> {
            deviceData.clear();
            deviceData.addAll(knownDevices.values());
            tableView.getSortOrder().clear();
            TableColumn<NetworkDevice, ?> ipColumn = tableView.getColumns().get(0);
            ipColumn.setSortType(TableColumn.SortType.ASCENDING);
            tableView.getSortOrder().add(ipColumn);
            tableView.sort();
        });
    }

    /**
     * Возвращает выбранную подсеть (из ввода или списка).
     *
     * @return Строка вида "ip/mask" или null, если не указана.
     */
    private String getSelectedSubnet() {
        if (manualSubnetField.getText() != null && !manualSubnetField.getText().trim().isEmpty()) {
            return manualSubnetField.getText().trim();
        }
        if (subnetComboBox.getValue() != null && !subnetComboBox.getValue().isEmpty()) {
            return subnetComboBox.getValue();
        }
        return null;
    }

    /**
     * Сравнивает два IP-адреса.
     *
     * @param ip1 Первый IP.
     * @param ip2 Второй IP.
     * @return Отрицательное, если ip1 < ip2, 0 если равны, положительное если ip1 > ip2.
     */
    private int compareIPs(String ip1, String ip2) {
        int[] p1 = parseIP(ip1);
        int[] p2 = parseIP(ip2);
        for (int i = 0; i < 4; i++) {
            if (p1[i] < p2[i]) return -1;
            if (p1[i] > p2[i]) return 1;
        }
        return 0;
    }

    /**
     * Преобразует IP-адрес в массив из 4 чисел.
     *
     * @param ip IP-адрес.
     * @return Массив int из 4 элементов.
     */
    private int[] parseIP(String ip) {
        int[] arr = new int[4];
        String[] tokens = ip.split("\\.");
        for (int i = 0; i < 4; i++) {
            if (i < tokens.length) {
                try {
                    arr[i] = Integer.parseInt(tokens[i]);
                } catch (NumberFormatException e) {
                    arr[i] = 0;
                }
            } else {
                arr[i] = 0;
            }
        }
        return arr;
    }

    /**
     * Отображает окно с результатами SNMP Walk для заданного IP.
     *
     * @param ipAddress IPv4-адрес.
     */
    private void showSnmpWalkWindow(String ipAddress) {
        Stage snmpStage = new Stage();
        snmpStage.initModality(Modality.WINDOW_MODAL);
        snmpStage.setTitle("SNMP Walk - " + ipAddress);
        BorderPane pane = new BorderPane();
        pane.setPadding(new Insets(10));
        TextArea resultArea = new TextArea();
        resultArea.setEditable(false);

        Task<List<String>> walkTask = new Task<>() {
            @Override
            protected List<String> call() throws IOException {
                return SNMP.snmpWalkEntireMIB(ipAddress);
            }
        };
        walkTask.setOnSucceeded(e -> {
            List<String> walkResult = walkTask.getValue();
            if (walkResult != null && !walkResult.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String line : walkResult) {
                    sb.append(line).append("\n");
                }
                resultArea.setText(sb.toString());
            } else {
                resultArea.setText("SNMP Walk не дал результатов или произошла ошибка.");
            }
        });
        walkTask.setOnFailed(e -> resultArea.setText("Ошибка при SNMP Walk: " + walkTask.getException().getMessage()));
        new Thread(walkTask).start();
        pane.setCenter(resultArea);
        Scene scene = new Scene(pane, 600, 400);
        snmpStage.setScene(scene);
        snmpStage.getIcons().add(new Image("/elogo.png"));
        snmpStage.show();
    }

    /**
     * Вызывается при завершении работы приложения. Останавливает сканирование и закрывает SNMP.
     *
     * @throws Exception Если произошла ошибка при завершении.
     */
    @Override
    public void stop() throws Exception {
        super.stop();
        stopPeriodicScan();
        SNMP.closeSnmp();
    }

    /**
     * Точка входа приложения.
     *
     * @param args Аргументы командной строки.
     */
    public static void main(String[] args) {
        launch(args);
    }
}
