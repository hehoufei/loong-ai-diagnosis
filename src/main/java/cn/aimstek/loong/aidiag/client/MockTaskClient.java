package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.dto.TaskDetail;
import org.springframework.stereotype.Component;

/**
 * Mock实现，仅用于无数据库环境下的测试。
 * 生产环境由 JdbcTaskClient(@Primary) 覆盖。
 */
@Component
public class MockTaskClient implements TaskClient {

    @Override
    public TaskDetail getTaskDetail(String taskId, String env) {
        TaskDetail detail = new TaskDetail();
        detail.setTaskId(taskId);
        detail.setTaskState("FAILED");
        detail.setErrorMessage("dispatch timeout");
        return detail;
    }
}
