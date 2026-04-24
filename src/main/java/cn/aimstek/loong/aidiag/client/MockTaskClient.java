package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.dto.TaskDetail;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
        detail.setWmsTaskNo(taskId);
        detail.setTaskState("FAILED");
        detail.setHandleState("WAITING");
        detail.setContainerCode("MOCK-C1");
        detail.setBusinessFrom("A01");
        detail.setBusinessTo("B01");
        detail.setErrorMessage("dispatch timeout");
        detail.setCreateTime(LocalDateTime.now().minusMinutes(10));
        return detail;
    }

    @Override
    public List<TaskDetail> findRelatedTasks(TaskDetail focusTask, LocalDateTime windowStart, LocalDateTime windowEnd, String env) {
        List<TaskDetail> tasks = new ArrayList<>();

        TaskDetail blocker = new TaskDetail();
        blocker.setTaskId(focusTask.getTaskId() + "-BLOCKER");
        blocker.setWmsTaskNo(focusTask.getWmsTaskNo());
        blocker.setTaskState("RUNNING");
        blocker.setHandleState("EXECUTING");
        blocker.setContainerCode(focusTask.getContainerCode());
        blocker.setBusinessFrom(focusTask.getBusinessFrom());
        blocker.setBusinessTo(focusTask.getBusinessTo());
        blocker.setErrorMessage("point occupied");
        blocker.setCreateTime(LocalDateTime.now().minusMinutes(15));
        tasks.add(blocker);

        TaskDetail impacted = new TaskDetail();
        impacted.setTaskId(focusTask.getTaskId() + "-IMPACTED");
        impacted.setWmsTaskNo(focusTask.getWmsTaskNo());
        impacted.setTaskState("WAITING");
        impacted.setHandleState("PENDING");
        impacted.setContainerCode(focusTask.getContainerCode());
        impacted.setBusinessFrom(focusTask.getBusinessFrom());
        impacted.setBusinessTo(focusTask.getBusinessTo());
        impacted.setCreateTime(LocalDateTime.now().minusMinutes(5));
        tasks.add(impacted);

        return tasks;
    }
}
