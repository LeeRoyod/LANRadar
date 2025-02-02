package lanradar;

import com.opencsv.exceptions.CsvException;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Содержит методы для сканирования сети.
 */
public class NetworkScanner {
    private static final Logger logger = LoggerFactory.getLogger(NetworkScanner.class);
    private static final int PING_TIMEOUT_MS = 400;
    private static final int MAX_PING_ATTEMPTS = 3;

    /**
     * Ищет устройства в диапазоне IP-адресов.
     *
     * @param startAddressStr Начальный IPv4-адрес.
     * @param endAddressStr   Конечный IPv4-адрес.
     * @return Список найденных устройств или null при ошибке.
     */
    public static List<NetworkDevice> findDevicesInSubnet(String startAddressStr, String endAddressStr) {
        try {
            InetAddress startAddress = InetAddress.getByName(startAddressStr);
            InetAddress endAddress = InetAddress.getByName(endAddressStr);
            if (startAddress.getAddress().length != 4 || endAddress.getAddress().length != 4) {
                logger.warn("Некорректные IPv4 адреса (start={}, end={})", startAddressStr, endAddressStr);
                return null;
            }

            List<NetworkDevice> connectedDevices = new ArrayList<>();
            ExecutorService executor = Executors.newFixedThreadPool(256);
            List<Future<NetworkDevice>> futures = new ArrayList<>();

            for (InetAddress currentAddress = startAddress;
                 !currentAddress.equals(endAddress);
                 currentAddress = UtilityNetwork.getNextAddress(currentAddress)) {
                String ipAddress = currentAddress.getHostAddress();
                Future<NetworkDevice> future = executor.submit(() -> createNetworkDevice(ipAddress));
                futures.add(future);
            }
            Future<NetworkDevice> future = executor.submit(() -> createNetworkDevice(endAddress.getHostAddress()));
            futures.add(future);

            for (Future<NetworkDevice> f : futures) {
                try {
                    NetworkDevice device = f.get();
                    if (device != null) {
                        connectedDevices.add(device);
                    }
                } catch (CancellationException | InterruptedException ex) {
                    logger.info("Задача сканирования прервана: {}", ex.getMessage(), ex);
                } catch (Exception e) {
                    logger.error("Ошибка при получении результата сканирования: {}", e.getMessage(), e);
                }
            }
            executor.shutdown();
            return connectedDevices;
        } catch (UnknownHostException e) {
            logger.error("Ошибка: некорректные адреса (start={}, end={}), msg={}", startAddressStr, endAddressStr, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Сканирует указанный список IP-адресов.
     *
     * @param ipAddresses Список IPv4-адресов.
     * @return Список найденных устройств.
     */
    public static List<NetworkDevice> findDevicesByIPs(List<String> ipAddresses) {
        if (ipAddresses == null || ipAddresses.isEmpty()) {
            return new ArrayList<>();
        }
        List<NetworkDevice> result = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(64);
        List<Future<NetworkDevice>> futures = new ArrayList<>();

        for (String ip : ipAddresses) {
            Future<NetworkDevice> future = executor.submit(() -> createNetworkDevice(ip));
            futures.add(future);
        }

        for (Future<NetworkDevice> f : futures) {
            try {
                NetworkDevice dev = f.get();
                if (dev != null) {
                    result.add(dev);
                }
            } catch (CancellationException | InterruptedException ex) {
                logger.info("Задача сканирования по списку IP прервана: {}", ex.getMessage(), ex);
            } catch (Exception e) {
                logger.error("Ошибка при создании объекта NetworkDevice: {}", e.getMessage(), e);
            }
        }
        executor.shutdown();
        return result;
    }

    /**
     * Создаёт устройство, если IP-адрес отвечает на пинг.
     *
     * @param ipAddress IPv4-адрес для проверки.
     * @return Объект NetworkDevice, если устройство доступно; иначе null.
     */
    private static NetworkDevice createNetworkDevice(String ipAddress) {
        try {
            InetAddress inetAddress = InetAddress.getByName(ipAddress);
            boolean isReachable = false;
            for (int i = 0; i < MAX_PING_ATTEMPTS; i++) {
                if (inetAddress.isReachable(PING_TIMEOUT_MS)) {
                    isReachable = true;
                    break;
                }
            }
            if (isReachable) {
                NetworkDevice device = new NetworkDevice(ipAddress);
                device.setDNSname(inetAddress.getHostName().equals(ipAddress) ? null : inetAddress.getHostName());

                macAddressResolverARP(device);
                if (device.getMacAddress() == null) {
                    macAddressResolverNetworkInterface(device);
                }
                NetworkDevice.findManufacturerName(device);
                if (SNMP.isPortSNMPOpen(ipAddress)) {
                    device.setSNMPAvailable(true);
                }
                return device;
            }
        } catch (IOException | InterruptedException | CsvException e) {
            if (e.getMessage() == null || !e.getMessage().contains("no further information")) {
                logger.error("Ошибка при создании сетевого устройства ({}): {}", ipAddress, e.getMessage(), e);
            }
        }
        return null;
    }

    /**
     * Определяет MAC-адрес через NetworkInterface.
     *
     * @param device Объект NetworkDevice.
     */
    public static void macAddressResolverNetworkInterface(NetworkDevice device) {
        String ipAddress = device.getIpAddress();
        try {
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(InetAddress.getByName(ipAddress));
            if (networkInterface != null) {
                byte[] macBytes = networkInterface.getHardwareAddress();
                if (macBytes != null) {
                    StringBuilder macAddress = new StringBuilder();
                    for (byte b : macBytes) {
                        macAddress.append(String.format("%02x-", b));
                    }
                    if (macAddress.length() > 0) {
                        macAddress.deleteCharAt(macAddress.length() - 1);
                    }
                    device.setMacAddress(macAddress.toString().toUpperCase());
                }
            }
        } catch (Exception e) {
            logger.info("Не удалось определить MAC через NetworkInterface ({}): {}", ipAddress, e.getMessage());
        }
    }

    /**
     * Определяет MAC-адрес с помощью команды ARP.
     *
     * @param device Объект NetworkDevice.
     * @throws IOException          При ошибке ввода/вывода.
     * @throws InterruptedException Если процесс прерван.
     */
    public static void macAddressResolverARP(NetworkDevice device) throws IOException, InterruptedException {
        String ipAddress = device.getIpAddress();
        String command;
        if (SystemUtils.IS_OS_WINDOWS) {
            command = "arp -a " + ipAddress;
        } else {
            command = "arp " + ipAddress;
        }
        Process process = Runtime.getRuntime().exec(command);
        process.waitFor();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(ipAddress)) {
                    String regexMac = "([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})";
                    if (line.matches(".*" + regexMac + ".*")) {
                        String mac = line.replaceAll(".*(" + regexMac + ").*", "$1");
                        device.setMacAddress(mac.toUpperCase().replace(":", "-"));
                    }
                }
            }
        }
    }
}
