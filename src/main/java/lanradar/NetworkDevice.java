package lanradar;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Представляет сетевое устройство с IP, MAC, производителем, DNS и флагом SNMP.
 */
public class NetworkDevice {

    /**
     * Статус устройства.
     */
    public enum DeviceStatus {
        NORMAL, NEW, CHANGED, LOST
    }

    private String ipAddress;
    private String manufacturerName;
    private String macAddress;
    private String DNSName;
    private boolean SNMPAvailable;
    private DeviceStatus status = DeviceStatus.NORMAL;
    private int scansAsNew = 0;

    /**
     * Создаёт новое устройство с указанным IP.
     *
     * @param ipAddress IP-адрес устройства.
     */
    public NetworkDevice(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    /**
     * Возвращает IP-адрес устройства.
     *
     * @return IP-адрес.
     */
    public String getIpAddress() {
        return ipAddress;
    }

    /**
     * Устанавливает IP-адрес устройства.
     *
     * @param ipAddress IP-адрес.
     */
    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    /**
     * Возвращает имя производителя.
     *
     * @return Имя производителя.
     */
    public String getManufacturerName() {
        return manufacturerName;
    }

    /**
     * Устанавливает имя производителя.
     *
     * @param manufacturerName Имя производителя.
     */
    public void setManufacturerName(String manufacturerName) {
        this.manufacturerName = manufacturerName;
    }

    /**
     * Возвращает MAC-адрес.
     *
     * @return MAC-адрес.
     */
    public String getMacAddress() {
        return macAddress;
    }

    /**
     * Устанавливает MAC-адрес.
     *
     * @param macAddress MAC-адрес.
     */
    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    /**
     * Возвращает DNS-имя устройства.
     *
     * @return DNS-имя или null, если не установлено.
     */
    public String getDNSname() {
        return DNSName;
    }

    /**
     * Устанавливает DNS-имя устройства.
     *
     * @param DNSname DNS-имя.
     */
    public void setDNSname(String DNSname) {
        this.DNSName = DNSname;
    }

    /**
     * Возвращает флаг доступности SNMP.
     *
     * @return true, если SNMP доступен.
     */
    public boolean getSNMPAvailable() {
        return SNMPAvailable;
    }

    /**
     * Устанавливает флаг доступности SNMP.
     *
     * @param SNMPAvailable true, если SNMP доступен.
     */
    public void setSNMPAvailable(boolean SNMPAvailable) {
        this.SNMPAvailable = SNMPAvailable;
    }

    /**
     * Возвращает статус устройства.
     *
     * @return Статус устройства.
     */
    public DeviceStatus getStatus() {
        return status;
    }

    /**
     * Устанавливает статус устройства.
     *
     * @param status Новый статус.
     */
    public void setStatus(DeviceStatus status) {
        this.status = status;
    }

    /**
     * Возвращает число сканирований в статусе NEW.
     *
     * @return Число сканирований.
     */
    public int getScansAsNew() {
        return scansAsNew;
    }

    /**
     * Устанавливает число сканирований в статусе NEW.
     *
     * @param scansAsNew Число сканирований.
     */
    public void setScansAsNew(int scansAsNew) {
        this.scansAsNew = scansAsNew;
    }

    /**
     * Возвращает строковое представление устройства.
     *
     * @return Строка с основными полями.
     */
    @Override
    public String toString() {
        return String.format("NetworkDevice{ip='%s', mac='%s', manufacturer='%s', DNS='%s', SNMP='%s'}",
                ipAddress, macAddress, manufacturerName, DNSName, SNMPAvailable);
    }

    /**
     * Определяет производителя по MAC-адресу, читая данные из файла ouiMAC.csv.
     *
     * @param device Объект устройства.
     * @throws IOException  Если произошла ошибка ввода/вывода.
     * @throws CsvException Если произошла ошибка при парсинге CSV.
     */
    public static void findManufacturerName(NetworkDevice device) throws IOException, CsvException {
        String macAddress = device.getMacAddress();
        if (macAddress == null) {
            return;
        }
        macAddress = macAddress.replaceAll("[:\\-]", "").toUpperCase();

        try (InputStream inputStream = UtilityNetwork.class.getResourceAsStream("/ouiMAC.csv");
             CSVReader reader = new CSVReader(new InputStreamReader(inputStream))) {
            if (inputStream == null) {
                return;
            }
            List<String[]> lines = reader.readAll();
            for (String[] line : lines) {
                if (line.length >= 3) {
                    String oui = line[1];
                    String organizationName = line[2];
                    if (macAddress.startsWith(oui)) {
                        device.setManufacturerName(organizationName);
                        break;
                    }
                }
            }
        }
    }
}
