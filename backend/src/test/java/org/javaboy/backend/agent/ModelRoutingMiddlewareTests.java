package org.javaboy.backend.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRoutingMiddlewareTests {

    @Test
    void routesModelFromRuntimeContext() {
        ModelCatalog catalog = mock(ModelCatalog.class);
        Model selectedModel = mock(Model.class);
        when(catalog.requireModel("qwen-max")).thenReturn(selectedModel);
        ModelRoutingMiddleware middleware = new ModelRoutingMiddleware(catalog);
        RuntimeContext context = RuntimeContext.builder()
                .put(ModelRoutingMiddleware.MODEL_KEY_CONTEXT_KEY, "qwen-max")
                .build();
        ModelCallInput original = new ModelCallInput(List.of(), List.of(), null, mock(Model.class));
        AtomicReference<ModelCallInput> captured = new AtomicReference<>();

        middleware.onModelCall(mock(Agent.class), context, original, routed -> {
            captured.set(routed);
            return Flux.empty();
        }).blockLast();

        assertSame(selectedModel, captured.get().model());
    }
}
