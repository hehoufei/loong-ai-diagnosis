package cn.aimstek.loong.aidiag.rule.rules;

import cn.aimstek.loong.aidiag.client.dto.*;
import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.rule.RuleProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎匹配逻辑单元测试。
 * 每条规则验证一个典型正例，必要时验证反例。
 */
class RulesTest {

    private DiagnosisContext buildContext(PlatformTask task, List<PlatformTaskItem> items) {
        return DiagnosisContext.builder()
                .traceId("test-trace")
                .task(task)
                .items(items != null ? items : new ArrayList<>())
                .relations(new ArrayList<>())
                .now(LocalDateTime.of(2025, 1, 1, 12, 0, 0))
                .build();
    }

    private PlatformTask buildTask(String state, LocalDateTime startTime) {
        PlatformTask task = new PlatformTask();
        task.setTaskNo("T001");
        task.setTaskState(state);
        task.setStartTime(startTime);
        return task;
    }

    private PlatformTaskItem buildItem(String itemNo, String state, List<PlatformCommand> commands) {
        PlatformTaskItem item = new PlatformTaskItem();
        item.setTaskItemNo(itemNo);
        item.setTaskItemState(state);
        item.setCommands(commands != null ? commands : new ArrayList<>());
        return item;
    }

    private PlatformCommand buildCmd(String state, String plcTaskNo, LocalDateTime startTime) {
        PlatformCommand cmd = new PlatformCommand();
        cmd.setCommandNo("C001");
        cmd.setCommandState(state);
        cmd.setPlcTaskNo(plcTaskNo);
        cmd.setStartTime(startTime);
        return cmd;
    }

    @Test
    void waitSplitStuckRule_matchesWhenStuckOver60s() {
        WaitSplitStuckRule rule = new WaitSplitStuckRule();
        injectProps(rule, new RuleProperties());

        PlatformTask task = buildTask("WAIT_SPLIT", LocalDateTime.of(2025, 1, 1, 11, 58, 0)); // 卡 120s
        DiagnosisContext ctx = buildContext(task, null);

        assertTrue(rule.match(ctx));
    }

    @Test
    void waitSplitStuckRule_notMatchWhenBelowThreshold() {
        WaitSplitStuckRule rule = new WaitSplitStuckRule();
        injectProps(rule, new RuleProperties());

        PlatformTask task = buildTask("WAIT_SPLIT", LocalDateTime.of(2025, 1, 1, 11, 59, 30)); // 卡 30s
        DiagnosisContext ctx = buildContext(task, null);

        assertFalse(rule.match(ctx));
    }

    @Test
    void waitPlanStuckRule_matchesWhenNoRunningItems() {
        WaitPlanStuckRule rule = new WaitPlanStuckRule();
        injectProps(rule, new RuleProperties());

        PlatformTask task = buildTask("WAIT_PLAN", LocalDateTime.of(2025, 1, 1, 11, 58, 0));
        List<PlatformTaskItem> items = List.of(
                buildItem("I1", "WAIT_PLAN", null),
                buildItem("I2", "WAIT_SPLIT", null)
        );
        DiagnosisContext ctx = buildContext(task, items);

        assertTrue(rule.match(ctx));
    }

    @Test
    void runningButItemsWaitingRule_matches() {
        RunningButItemsWaitingRule rule = new RunningButItemsWaitingRule();

        PlatformTask task = buildTask("RUNNING", LocalDateTime.of(2025, 1, 1, 11, 55, 0));
        List<PlatformTaskItem> items = List.of(
                buildItem("I1", "WAIT_PLAN", null),
                buildItem("I2", "WAIT_SPLIT", null)
        );
        DiagnosisContext ctx = buildContext(task, items);

        assertTrue(rule.match(ctx));
    }

    @Test
    void commandNotSentRule_matches() {
        CommandNotSentRule rule = new CommandNotSentRule();

        PlatformTask task = buildTask("RUNNING", LocalDateTime.of(2025, 1, 1, 11, 55, 0));
        List<PlatformCommand> cmds = List.of(
                buildCmd("WAIT", null, null),
                buildCmd("WAIT", null, null)
        );
        List<PlatformTaskItem> items = List.of(buildItem("I1", "RUNNING", cmds));
        DiagnosisContext ctx = buildContext(task, items);

        assertTrue(rule.match(ctx));
    }

    @Test
    void plcNoResponseRule_matches() {
        PlcNoResponseRule rule = new PlcNoResponseRule();

        PlatformTask task = buildTask("RUNNING", LocalDateTime.of(2025, 1, 1, 11, 55, 0));
        List<PlatformCommand> cmds = List.of(
                buildCmd("SENT", null, LocalDateTime.of(2025, 1, 1, 11, 59, 0))
        );
        List<PlatformTaskItem> items = List.of(buildItem("I1", "RUNNING", cmds));
        DiagnosisContext ctx = buildContext(task, items);

        assertTrue(rule.match(ctx));
    }

    @Test
    void plcNoResponseRule_noMatchWhenPlcTaskNoPresent() {
        PlcNoResponseRule rule = new PlcNoResponseRule();

        PlatformTask task = buildTask("RUNNING", LocalDateTime.of(2025, 1, 1, 11, 55, 0));
        List<PlatformCommand> cmds = List.of(
                buildCmd("SENT", "PLC_1001", LocalDateTime.of(2025, 1, 1, 11, 59, 0))
        );
        List<PlatformTaskItem> items = List.of(buildItem("I1", "RUNNING", cmds));
        DiagnosisContext ctx = buildContext(task, items);

        assertFalse(rule.match(ctx));
    }

    @Test
    void commandTimeoutRule_matches() {
        CommandTimeoutRule rule = new CommandTimeoutRule();
        injectProps(rule, new RuleProperties());

        PlatformTask task = buildTask("RUNNING", LocalDateTime.of(2025, 1, 1, 11, 55, 0));
        // startTime 11:50:00, now 12:00:00 → 600s > 300s
        List<PlatformCommand> cmds = List.of(
                buildCmd("SENT", "PLC_1001", LocalDateTime.of(2025, 1, 1, 11, 50, 0))
        );
        List<PlatformTaskItem> items = List.of(buildItem("I1", "RUNNING", cmds));
        DiagnosisContext ctx = buildContext(task, items);

        assertTrue(rule.match(ctx));
    }

    @Test
    void allItemsDoneButTaskNotFinishedRule_matches() {
        AllItemsDoneButTaskNotFinishedRule rule = new AllItemsDoneButTaskNotFinishedRule();

        PlatformTask task = buildTask("RUNNING", LocalDateTime.of(2025, 1, 1, 11, 55, 0));
        List<PlatformTaskItem> items = List.of(
                buildItem("I1", "SUCCESS", null),
                buildItem("I2", "SUCCESS", null)
        );
        DiagnosisContext ctx = buildContext(task, items);

        assertTrue(rule.match(ctx));
    }

    @Test
    void hasCancelledItemsRule_matches() {
        HasCancelledItemsRule rule = new HasCancelledItemsRule();

        PlatformTask task = buildTask("RUNNING", LocalDateTime.of(2025, 1, 1, 11, 55, 0));
        List<PlatformTaskItem> items = List.of(
                buildItem("I1", "RUNNING", null),
                buildItem("I2", "CANCEL", null)
        );
        DiagnosisContext ctx = buildContext(task, items);

        assertTrue(rule.match(ctx));
    }

    @Test
    void taskPausedRule_matchesWhenTaskPaused() {
        TaskPausedRule rule = new TaskPausedRule();

        PlatformTask task = buildTask("RUNNING", LocalDateTime.of(2025, 1, 1, 11, 55, 0));
        task.setPaused("YES");
        DiagnosisContext ctx = buildContext(task, Collections.emptyList());

        assertTrue(rule.match(ctx));
    }

    @Test
    void taskGroupNotSplitRule_matches() {
        TaskGroupNotSplitRule rule = new TaskGroupNotSplitRule();
        injectProps(rule, new RuleProperties());

        PlatformTask task = buildTask("WAIT_SPLIT", LocalDateTime.of(2025, 1, 1, 11, 55, 0));
        task.setGroupCode("G001");

        PlatformTaskGroup group = new PlatformTaskGroup();
        group.setGroupCode("G001");
        group.setSplitFinish("NOT_FINISH");

        DiagnosisContext ctx = DiagnosisContext.builder()
                .traceId("test-trace")
                .task(task)
                .items(new ArrayList<>())
                .relations(new ArrayList<>())
                .group(group)
                .now(LocalDateTime.of(2025, 1, 1, 12, 0, 0))
                .build();

        assertTrue(rule.match(ctx));
    }

    @Test
    void fallbackRule_alwaysMatches() {
        FallbackRule rule = new FallbackRule();

        PlatformTask task = buildTask("WHATEVER", LocalDateTime.of(2025, 1, 1, 11, 55, 0));
        DiagnosisContext ctx = buildContext(task, null);

        assertTrue(rule.match(ctx));
    }

    /**
     * 通过反射注入 RuleProperties，因为抽象基类用 @Autowired 注入。
     */
    private void injectProps(Object rule, RuleProperties props) {
        try {
            var field = rule.getClass().getSuperclass().getDeclaredField("ruleProperties");
            field.setAccessible(true);
            field.set(rule, props);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
