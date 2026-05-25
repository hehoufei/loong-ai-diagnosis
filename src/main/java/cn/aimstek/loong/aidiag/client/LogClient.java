package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.dto.ReportEventDetail;

import java.util.Collections;
import java.util.List;

public interface LogClient {

    List<String> queryLogs(String taskNo, String env);

    /**
     * 查询任务的上报事件历史（对应 loong-platform 的 sc_report_detail 表）。
     * 默认返回空列表，避免破坏未实现的子类。
     *
     * @param taskNo 任务号
     * @param env    环境标识
     * @return 上报事件列表，按时间倒序
     */
    default List<ReportEventDetail> queryReportEvents(String taskNo, String env) {
        return Collections.emptyList();
    }
}
