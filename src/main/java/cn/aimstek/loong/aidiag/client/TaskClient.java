package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.dto.TaskDetail;

public interface TaskClient {

    TaskDetail getTaskDetail(String taskId, String env);
}
