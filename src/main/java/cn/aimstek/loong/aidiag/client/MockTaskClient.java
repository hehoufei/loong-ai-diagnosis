package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.dto.TaskDetail;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mock实现，仅用于无数据库环境下的测试。
 * 生产环境由 HttpTaskClient(@Primary) 覆盖。
 */
@Component
public class MockTaskClient implements TaskClient {

    @Override
    public TaskDetail getTaskDetail(String taskNo, String env) {
        TaskDetail detail = new TaskDetail();
        detail.setTaskId(taskNo);
        detail.setTaskNo(taskNo);
        detail.setRootTaskNo("ROOT-" + taskNo);
        detail.setGroupCode("GRP-MOCK");
        detail.setTaskState("FAILED");
        detail.setPaused("N");
        detail.setStartNode("A01");
        detail.setEndNode("B01");
        detail.setBizType("INBOUND");
        detail.setTaskType("MOVE");
        detail.setPriority(5);
        detail.setCreateTime(LocalDateTime.now().minusMinutes(10));
        return detail;
    }

    @Override
    public List<TaskDetail> findRelatedTasks(TaskDetail focusTask, LocalDateTime windowStart, LocalDateTime windowEnd, String env) {
        List<TaskDetail> tasks = new ArrayList<>();

        TaskDetail blocker = new TaskDetail();
        blocker.setTaskId(focusTask.getTaskNo() + "-BLOCKER");
        blocker.setTaskNo(focusTask.getTaskNo() + "-BLOCKER");
        blocker.setRootTaskNo(focusTask.getRootTaskNo());
        blocker.setGroupCode(focusTask.getGroupCode());
        blocker.setTaskState("RUNNING");
        blocker.setPaused("N");
        blocker.setStartNode(focusTask.getStartNode());
        blocker.setEndNode(focusTask.getEndNode());
        blocker.setCreateTime(LocalDateTime.now().minusMinutes(15));
        tasks.add(blocker);

        TaskDetail impacted = new TaskDetail();
        impacted.setTaskId(focusTask.getTaskNo() + "-IMPACTED");
        impacted.setTaskNo(focusTask.getTaskNo() + "-IMPACTED");
        impacted.setRootTaskNo(focusTask.getRootTaskNo());
        impacted.setGroupCode(focusTask.getGroupCode());
        impacted.setPreStartTaskNo(focusTask.getTaskNo());
        impacted.setTaskState("WAITING");
        impacted.setPaused("N");
        impacted.setStartNode(focusTask.getStartNode());
        impacted.setEndNode(focusTask.getEndNode());
        impacted.setCreateTime(LocalDateTime.now().minusMinutes(5));
        tasks.add(impacted);

        return tasks;
    }

    @Override
    public List<TaskDetail.CommandDetail> getCommands(String taskNo, String env) {
        return Collections.emptyList();
    }
}
