package org.javaboy.backend.web;

import org.javaboy.backend.agent.ModelCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelCatalog modelCatalog;

    public ModelController(ModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    @GetMapping
    public List<ModelCatalog.ModelOption> listModels() {
        return modelCatalog.listOptions();
    }
}
