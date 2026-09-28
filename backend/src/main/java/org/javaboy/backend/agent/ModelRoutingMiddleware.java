package org.javaboy.backend.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import reactor.core.publisher.Flux;

import java.util.function.Function;

public class ModelRoutingMiddleware implements MiddlewareBase {

    public static final String MODEL_KEY_CONTEXT_KEY = "sreagent.model-key";

    private final ModelCatalog modelCatalog;

    public ModelRoutingMiddleware(ModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        String modelKey = context.get(MODEL_KEY_CONTEXT_KEY, String.class);
        if (modelKey == null || modelKey.isBlank()) {
            return Flux.error(new IllegalStateException("当前调用缺少会话模型配置"));
        }
        ModelCallInput routedInput = new ModelCallInput(
                input.messages(),
                input.tools(),
                input.options(),
                modelCatalog.requireModel(modelKey));
        return next.apply(routedInput);
    }
}
