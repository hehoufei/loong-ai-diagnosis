package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.dto.TaskDetail;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskClient {

    TaskDetail getTaskDetail(String taskId, String env);

    List<TaskDetail> findRelatedTasks(TaskDetail focusTask, LocalDateTime windowStart, LocalDateTime windowEnd, String env);
}
