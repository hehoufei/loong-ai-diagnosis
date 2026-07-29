package cn.aimstek.loong.aidiag.qltool.service;

import cn.aimstek.loong.aidiag.qltool.config.QlToolProperties.DeviceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QlToolDataStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsLayoutAndDeleteHistoryOutsideApplicationPackage() {
        String oldUserHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            QlToolDataStore store = new QlToolDataStore(mapper);
            store.init();

            DeviceConfig device = new DeviceConfig();
            device.setDeviceId("CV-03");
            device.setDeviceName("恒申2F输送线03");
            device.setDeviceType("CONVEYOR_LINE");
            device.setIp("192.168.53.222");
            device.setPort(20005);
            store.saveDevices(List.of(device));

            QlToolDataStore.DeviceSnapshot devices = store.loadDevices();
            assertThat(devices.exists()).isTrue();
            assertThat(devices.devices()).hasSize(1);
            assertThat(devices.devices().get(0).getIp()).isEqualTo("192.168.53.222");
            assertThat(Files.exists(tempDir.resolve(".loong-ai-diagnosis/ql-tool/devices.json"))).isTrue();

            ArrayNode layout = mapper.createArrayNode();
            ObjectNode point = mapper.createObjectNode();
            point.put("code", "23050");
            point.put("x", 100);
            point.put("y", 200);
            point.set("links", mapper.createArrayNode().add("23051"));
            layout.add(point);

            store.saveLayout("CV-03", layout);
            QlToolDataStore.LayoutSnapshot snapshot = store.loadLayout("CV-03");

            assertThat(snapshot.exists()).isTrue();
            assertThat(snapshot.layout()).isEqualTo(layout);
            assertThat(Files.exists(tempDir.resolve(".loong-ai-diagnosis/ql-tool/layouts/CV-03.json"))).isTrue();

            store.appendDeleteRecord("CV-03", 300085L, "23064", "MAP",
                    "REMOVE", true, "删除指令已写入 PLC");
            store.appendDeleteRecord("CV-03", 300086L, "", "TASK_TABLE",
                    "REMOVE", false, "设备已断开");

            var history = store.loadDeleteHistory("CV-03");
            assertThat(history).hasSize(2);
            assertThat(history.get(0).taskNo()).isEqualTo(300086L);
            assertThat(history.get(0).result()).isEqualTo("FAILED");
            assertThat(history.get(1).pointCode()).isEqualTo("23064");

            assertThat(store.clearDeleteHistory("CV-03")).isEqualTo(2);
            assertThat(store.loadDeleteHistory("CV-03")).isEmpty();

            store.clearLayout("CV-03");
            assertThat(store.loadLayout("CV-03").exists()).isTrue();
            assertThat(store.loadLayout("CV-03").layout()).isEmpty();
        } finally {
            if (oldUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", oldUserHome);
            }
        }
    }
}
