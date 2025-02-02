package lanradar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Утилиты для работы с IP-адресами, подсетями и сетевыми интерфейсами.
 */
public class UtilityNetwork {

    private static final Logger logger = LoggerFactory.getLogger(UtilityNetwork.class);

    /**
     * Вычисляет минимальный и максимальный хост-адрес для заданной подсети.
     *
     * @param subnet Подсеть в формате "ip/mask" (например, "192.168.0.99/24").
     * @return Список из двух IP-адресов: [minHost, maxHost]. При ошибке возвращается пустой список.
     */
    public static List<String> calculateHostRange(String subnet) {
        List<String> result = new ArrayList<>();
        try {
            if (!subnet.matches("^\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}$")) {
                throw new IllegalArgumentException("Некорректный формат подсети");
            }
            String[] parts = subnet.split("/");
            String ipAddress = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            InetAddress inetAddress = InetAddress.getByName(ipAddress);
            byte[] ipAddressBytes = inetAddress.getAddress();

            for (int i = prefixLength; i < ipAddressBytes.length * 8; i++) {
                int byteIndex = i / 8;
                int bitIndex = 7 - (i % 8);
                ipAddressBytes[byteIndex] &= ~(1 << bitIndex);
            }
            ipAddressBytes[ipAddressBytes.length - 1]++;
            InetAddress minHost = InetAddress.getByAddress(ipAddressBytes);

            int hostBits = 32 - prefixLength;
            for (int i = 0; i < hostBits; i++) {
                int byteIndex = (31 - i) / 8;
                int bitIndex = 7 - ((31 - i) % 8);
                ipAddressBytes[byteIndex] |= (1 << bitIndex);
            }
            ipAddressBytes[ipAddressBytes.length - 1]--;
            InetAddress maxHost = InetAddress.getByAddress(ipAddressBytes);

            result.add(minHost.getHostAddress());
            result.add(maxHost.getHostAddress());
        } catch (Exception e) {
            logger.error("Ошибка при вычислении диапазона ({}): {}", subnet, e.getMessage(), e);
        }
        return result;
    }

    /**
     * Возвращает следующий IP-адрес (currentAddress + 1).
     *
     * @param currentAddress Текущий IPv4-адрес.
     * @return Следующий IPv4-адрес.
     */
    public static InetAddress getNextAddress(InetAddress currentAddress) {
        byte[] addressBytes = currentAddress.getAddress();
        for (int i = addressBytes.length - 1; i >= 0; i--) {
            if (++addressBytes[i] != 0) {
                break;
            }
        }
        try {
            return InetAddress.getByAddress(addressBytes);
        } catch (UnknownHostException e) {
            logger.error("Ошибка при получении следующего адреса: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Возвращает список подсетей (ip/mask) для всех активных сетевых интерфейсов.
     *
     * @return Список строк вида "ip/mask".
     */
    public static List<String> listAdapterSubnets() {
        List<String> subnets = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress().getAddress().length == 4 && !ia.getAddress().isLoopbackAddress()) {
                        String subnet = ia.getAddress().getHostAddress() + "/" + ia.getNetworkPrefixLength();
                        subnets.add(subnet);
                    }
                }
            }
        } catch (SocketException e) {
            logger.error("Ошибка при получении подсетей: {}", e.getMessage(), e);
        }
        return subnets;
    }
}
