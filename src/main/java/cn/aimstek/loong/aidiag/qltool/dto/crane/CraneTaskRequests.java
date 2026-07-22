package cn.aimstek.loong.aidiag.qltool.dto.crane;

import lombok.Data;

/**
 * 堆垛机任务下发请求 DTO 集合（对应 LoongAssist 的 *TaskModel）。
 */
public final class CraneTaskRequests {

    private CraneTaskRequests() {}

    /** 搬运任务（取货+放货） */
    @Data
    public static class CarryTaskRequest {
        private long taskNo;
        private int pickupLine;
        private int pickupCol;
        private int pickupRow;
        private int dropoffLine;
        private int dropoffCol;
        private int dropoffRow;
        private int cargoWidth;
        private int cargoHeight;
        private int cargoDepth;
    }

    /** 行走(移动)任务 */
    @Data
    public static class MoveTaskRequest {
        private long taskNo;
        private int targetLine;
        private int targetCol;
        private int targetRow;
        private int cargoWidth;
        private int cargoHeight;
        private int cargoDepth;
    }

    /** 取货任务 */
    @Data
    public static class PickupTaskRequest {
        private long taskNo;
        private int pickupLine;
        private int pickupCol;
        private int pickupRow;
        private int cargoWidth;
        private int cargoHeight;
        private int cargoDepth;
    }

    /** 放货任务 */
    @Data
    public static class DropoffTaskRequest {
        private long taskNo;
        private int dropoffLine;
        private int dropoffCol;
        private int dropoffRow;
        private int cargoWidth;
        private int cargoHeight;
        private int cargoDepth;
    }

    /** 清除任务 */
    @Data
    public static class ClearTaskRequest {
        private long taskNo;
    }
}
