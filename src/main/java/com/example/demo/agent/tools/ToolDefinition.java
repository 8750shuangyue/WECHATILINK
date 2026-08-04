package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolDefinition {

    private String name;
    private String description;
    private Map<String, Property> parameters;
    private List<String> required;

    public ToolDefinition() {
        this.parameters = new LinkedHashMap<>();
        this.required = new ArrayList<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Property> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Property> parameters) {
        this.parameters = parameters;
    }

    public List<String> getRequired() {
        return required;
    }

    public void setRequired(List<String> required) {
        this.required = required;
    }

    public void addParameter(String name, String type, String description) {
        Property property = new Property();
        property.setType(type);
        property.setDescription(description);
        this.parameters.put(name, property);
    }

    public void addRequired(String parameterName) {
        if (!this.required.contains(parameterName)) {
            this.required.add(parameterName);
        }
    }

    public Map<String, Object> toSchema() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", this.name);
        function.put("description", this.description);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Property> entry : this.parameters.entrySet()) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", entry.getValue().getType());
            prop.put("description", entry.getValue().getDescription());
            properties.put(entry.getKey(), prop);
        }
        params.put("properties", properties);
        if (!this.required.isEmpty()) {
            params.put("required", this.required);
        }

        function.put("parameters", params);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);
        return schema;
    }

    public String toJson() {
        return JSON.toJSONString(toSchema());
    }

    public static class Property {
        private String type;
        private String description;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class Builder {
        private final ToolDefinition definition;

        public Builder() {
            this.definition = new ToolDefinition();
        }

        public Builder name(String name) {
            definition.setName(name);
            return this;
        }

        public Builder description(String description) {
            definition.setDescription(description);
            return this;
        }

        public Builder parameter(String name, String type, String description) {
            definition.addParameter(name, type, description);
            return this;
        }

        public Builder required(String parameterName) {
            definition.addRequired(parameterName);
            return this;
        }

        public ToolDefinition build() {
            return definition;
        }
    }
}