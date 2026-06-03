package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.storagetask.DailyReportService;
import cn.aimstek.loong.aidiag.storagetask.dto.DailyReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 每日跑库报告 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/storage-task/daily-report")
@RequiredArgsConstructor
public class DailyReportController {

    private final DailyReportService reportService;

    /**
     * 获取某天全部巷道的报告.
     * GET /api/v1/storage-task/daily-report?date=2026-06-03
     */
    @GetMapping
    public Response<List<DailyReport>> getReports(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        try {
            if (from != null && to != null) {
                return BaseResponse.success(reportService.getReportRange(from, to));
            }
            if (date == null) {
                date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            }
            return BaseResponse.success(reportService.getReports(date));
        } catch (Exception e) {
            log.error("获取每日报告失败", e);
            return BaseResponse.failure("REPORT_ERROR", e.getMessage());
        }
    }

    /**
     * 获取某天某巷道的报告.
     * GET /api/v1/storage-task/daily-report/{aisle}?date=2026-06-03
     */
    @GetMapping("/{aisle}")
    public Response<DailyReport> getReport(@PathVariable int aisle,
                                           @RequestParam(required = false) String date) {
        try {
            if (date == null) {
                date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            }
            DailyReport r = reportService.getReport(date, aisle);
            return BaseResponse.success(r);
        } catch (Exception e) {
            log.error("获取巷道{}报告失败", aisle, e);
            return BaseResponse.failure("REPORT_ERROR", e.getMessage());
        }
    }

    /**
     * 手动触发生成当天报告.
     * POST /api/v1/storage-task/daily-report/generate?date=2026-06-03
     */
    @PostMapping("/generate")
    public Response<List<DailyReport>> generate(@RequestParam(required = false) String date) {
        try {
            if (date == null) {
                date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            }
            return BaseResponse.success(reportService.generate(date));
        } catch (Exception e) {
            log.error("生成报告失败", e);
            return BaseResponse.failure("GENERATE_ERROR", e.getMessage());
        }
    }
}
