package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.dto.CollectedDataInfo;
import cn.aimstek.loong.aidiag.dto.DeviceLockInfo;
import cn.aimstek.loong.aidiag.dto.GoodsStateInfo;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.dto.TaskGroupMember;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

public interface TaskClient {

    TaskDetail getTaskDetail(String taskNo, String env);

    List<TaskDetail> findRelatedTasks(TaskDetail focusTask, LocalDateTime windowStart, LocalDateTime windowEnd, String env);

    List<TaskDetail.CommandDetail> getCommands(String taskNo, String env);

    /** 查询设备锁定信息（默认返回空列表，仅 JdbcTaskClient 实现） */
    default List<DeviceLockInfo> queryDeviceLocks(String deviceCode) {
        return Collections.emptyList();
    }

    /** 查询采集数据记录（默认返回空列表，仅 JdbcTaskClient 实现） */
    default List<CollectedDataInfo> queryCollectedData(String taskNo) {
        return Collections.emptyList();
    }

    /** 查询任务组成员（默认返回空列表，仅 JdbcTaskClient 实现） */
    default List<TaskGroupMember> queryTaskGroupMembers(String groupCode) {
        return Collections.emptyList();
    }

    /** 查询货物实时状态（默认返回空列表，仅 JdbcTaskClient 实现） */
    default List<GoodsStateInfo> queryGoodsState(String taskNo) {
        return Collections.emptyList();
    }
}
