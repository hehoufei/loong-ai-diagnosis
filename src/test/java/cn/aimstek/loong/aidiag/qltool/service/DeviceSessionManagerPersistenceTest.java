package cn.aimstek.loong.aidiag.qltool.service;

import cn.aimstek.loong.aidiag.qltool.config.QlToolProperties;
import cn.aimstek.loong.aidiag.qltool.config.QlToolProperties.DeviceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceSessionManagerPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void restoresAddedUpdatedAndDeletedDevicesAfterRestart() {
        String oldUserHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            QlToolProperties properties = propertiesWithBaseline();

            DeviceSessionManager first = manager(properties);
            first.init();

            DeviceConfig added = device("CV-05", "恒申2F输送线05", "CONVEYOR_LINE", "10.15.58.2", 102);
            first.addDevice(added);

            DeviceConfig changed = device("CR-01", "更新后的堆垛机", "STACKER_CRANE", "10.15.57.99", 102);
            first.updateDevice("CR-01", changed);
            first.removeDevice("CV-BASE");

            DeviceSessionManager restarted = manager(propertiesWithBaseline());
            restarted.init();

            assertThat(restarted.getDeviceItem("CR-01").getDeviceName()).isEqualTo("更新后的堆垛机");
            assertThat(restarted.getDeviceItem("CR-01").getIp()).isEqualTo("10.15.57.99");
            assertThat(restarted.getDeviceItem("CV-05").getDeviceName()).isEqualTo("恒申2F输送线05");
            assertThatThrownByMissing(restarted, "CV-BASE");
        } finally {
            if (oldUserHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", oldUserHome);
        }
    }

    @Test
    void restoresSitesAndDeviceAssignmentsAfterRestart() {
        String oldUserHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            DeviceSessionManager first = manager(propertiesWithBaseline());
            first.init();
            first.addSite("比图美欣达");

            DeviceConfig added = device("BT-CV-01", "比图输送线01", "CONVEYOR_LINE", "10.20.1.10", 102);
            added.setSiteName("比图美欣达");
            first.addDevice(added);

            DeviceSessionManager restarted = manager(propertiesWithBaseline());
            restarted.init();

            assertThat(restarted.getSites())
                    .extracting("siteName")
                    .containsExactly("恒申美达", "比图美欣达");
            assertThat(restarted.getDeviceItem("BT-CV-01").getSiteName()).isEqualTo("比图美欣达");
            assertThat(restarted.getSites().stream()
                    .filter(site -> site.getSiteName().equals("比图美欣达"))
                    .findFirst().orElseThrow().getConveyorCount()).isEqualTo(1);
        } finally {
            if (oldUserHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", oldUserHome);
        }
    }

    private DeviceSessionManager manager(QlToolProperties properties) {
        QlToolDataStore store = new QlToolDataStore(new ObjectMapper().findAndRegisterModules());
        store.init();
        if (!store.loadDevices().exists()) {
            store.saveDevices(properties.getDevices());
        }
        return new DeviceSessionManager(properties, store);
    }

    private QlToolProperties propertiesWithBaseline() {
        QlToolProperties properties = new QlToolProperties();
        properties.setDevices(List.of(
                device("CR-01", "默认堆垛机", "STACKER_CRANE", "10.15.57.17", 102),
                device("CV-BASE", "默认输送线", "CONVEYOR_LINE", "10.15.58.80", 102)
        ));
        return properties;
    }

    private DeviceConfig device(String id, String name, String type, String ip, int port) {
        DeviceConfig device = new DeviceConfig();
        device.setDeviceId(id);
        device.setDeviceName(name);
        device.setDeviceType(type);
        device.setIp(ip);
        device.setPort(port);
        return device;
    }

    private void assertThatThrownByMissing(DeviceSessionManager manager, String deviceId) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.getDeviceItem(deviceId))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
