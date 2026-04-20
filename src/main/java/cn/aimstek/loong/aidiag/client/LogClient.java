package cn.aimstek.loong.aidiag.client;

import java.util.List;

public interface LogClient {

    List<String> queryLogs(String taskId, String env);
}
