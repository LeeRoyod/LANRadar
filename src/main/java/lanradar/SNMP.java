package lanradar;

import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Предоставляет методы для проверки SNMP-порта и выполнения SNMP Walk.
 */
public class SNMP {

    private static final Logger logger = LoggerFactory.getLogger(SNMP.class);
    private static Snmp snmp = null;
    private static TransportMapping<UdpAddress> transport = null;

    /**
     * Инициализирует общий объект SNMP и запускает транспорт.
     *
     * @throws IOException Если не удалось создать или запустить транспорт.
     */
    public static synchronized void initSnmp() throws IOException {
        if (snmp == null) {
            transport = new DefaultUdpTransportMapping();
            snmp = new Snmp(transport);
            transport.listen();
        }
    }

    /**
     * Закрывает объект SNMP и транспорт.
     *
     * @throws IOException Если произошла ошибка ввода/вывода при закрытии.
     */
    public static synchronized void closeSnmp() throws IOException {
        if (snmp != null) {
            snmp.close();
            transport.close();
            snmp = null;
            transport = null;
        }
    }

    /**
     * Создаёт объект CommunityTarget для SNMP-связи.
     *
     * @param ipAddress IPv4-адрес.
     * @param community SNMP-сообщество (например, "public").
     * @return Настроенный объект CommunityTarget.
     */
    private static CommunityTarget createCommunityTarget(String ipAddress, String community) {
        CommunityTarget target = new CommunityTarget();
        target.setCommunity(new OctetString(community));
        target.setVersion(SnmpConstants.version2c);
        target.setAddress(new UdpAddress(ipAddress + "/161"));
        target.setRetries(2);
        target.setTimeout(1500);
        return target;
    }

    /**
     * Проверяет, отвечает ли SNMP-порт на заданном IP.
     *
     * @param ipAddress IPv4-адрес.
     * @return true, если SNMP отвечает, иначе false.
     */
    public static boolean isPortSNMPOpen(String ipAddress) {
        if (snmp == null) {
            throw new IllegalStateException("SNMP not initialized");
        }
        try {
            CommunityTarget target = createCommunityTarget(ipAddress, "public");
            PDU pdu = new PDU();
            pdu.setType(PDU.GETNEXT);
            pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.1.0")));
            ResponseEvent response = snmp.get(pdu, target);
            return (response.getResponse() != null);
        } catch (IOException e) {
            logger.error("Ошибка проверки SNMP-порта ({}): {}", ipAddress, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Выполняет SNMP Walk, начиная с OID "1", и возвращает результаты.
     *
     * @param ipAddress IPv4-адрес.
     * @return Список строк вида "OID: <oid>, Value: <value>".
     * @throws IOException Если произошла ошибка при обмене SNMP.
     */
    public static List<String> snmpWalkEntireMIB(String ipAddress) throws IOException {
        List<String> resultList = new ArrayList<>();
        if (snmp == null) {
            initSnmp();
        }
        CommunityTarget target = createCommunityTarget(ipAddress, "public");
        OID currentOid = new OID("1");
        while (true) {
            PDU pdu = new PDU();
            pdu.setType(PDU.GETNEXT);
            pdu.add(new VariableBinding(currentOid));
            ResponseEvent event = snmp.send(pdu, target);
            if (event == null || event.getResponse() == null) {
                break;
            }
            PDU responsePdu = event.getResponse();
            VariableBinding vb = responsePdu.get(0);
            if (vb == null) {
                break;
            }
            OID nextOid = vb.getOid();
            if (nextOid == null || nextOid.compareTo(currentOid) <= 0 || vb.getVariable() instanceof Null) {
                break;
            }
            String line = "OID: " + nextOid + ", Value: " + vb.getVariable();
            resultList.add(line);
            currentOid = nextOid;
        }
        return resultList;
    }
}
