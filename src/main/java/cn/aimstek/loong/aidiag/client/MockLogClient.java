package cn.aimstek.loong.aidiag.client;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MockLogClient implements LogClient {

    @Override
    public List<String> queryLogs(String taskNo, String env) {
        return List.of(
            "2026-03-03 10:31:04 ERROR dispatch_timeout queue=etl_high wait=600s",
            "2026-03-03 10:31:05 ERROR quota_exceeded queue=etl_high"
        );
    }
}
