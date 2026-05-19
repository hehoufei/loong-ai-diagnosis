package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.client.PlatformClient;
import cn.aimstek.loong.aidiag.facade.DiagnosisFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentChatController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AgentChatControllerTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private DiagnosisFacade diagnosisFacade;

    @Mock
    private PlatformClient platformClient;

    private AgentChatController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentChatController(chatModel, diagnosisFacade, platformClient);
    }

    @Test
    void getModels_returnsModelList() {
        // When
        var response = controller.getModels();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals(3, response.getBody().getData().size());
        assertEquals("glm-4-flash", response.getBody().getData().get(0).getId());
        assertTrue(response.getBody().getData().get(0).isDefault());
    }

    @Test
    void switchModel_success() {
        // Given
        AgentChatController.SwitchModelRequest request = new AgentChatController.SwitchModelRequest();
        request.setModelName("glm-4");

        // When
        var response = controller.switchModel(request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
    }

    @Test
    void getEnv_returnsEnvironmentInfo() {
        // When
        var response = controller.getEnv();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals("production", response.getBody().getData().getEnvironment());
        assertEquals("glm-4-flash", response.getBody().getData().getModelName());
    }

    @Test
    void chat_returnsSSEEmitter() {
        // Given
        AgentChatController.ChatRequest request = new AgentChatController.ChatRequest();
        request.setSessionId("test-session");
        request.setMessage("诊断任务 T001");

        // When
        var emitter = controller.chat(request);

        // Then
        assertNotNull(emitter);
        // Note: 完整的 SSE 测试需要集成测试环境
    }
}
