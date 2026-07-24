package com.example.demo.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;

public class ToolCall {

    private String id;
    private String type;
    private String toolName;
    private JSONObject arguments;

    public ToolCall() {
    }

    public ToolCall(String id, String type, String toolName, JSONObject arguments) {
        this.id = id;
        this.type = type;
        this.toolName = toolName;
        this.arguments = arguments;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public JSONObject getArguments() {
        return arguments;
    }

    public void setArguments(JSONObject arguments) {
        this.arguments = arguments;
    }

    private static JSONObject getMessageFromResponse(JSONObject response) {
        if (response == null) {
            return null;
        }
        
        JSONArray choices = response.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            JSONObject output = response.getJSONObject("output");
            if (output != null) {
                choices = output.getJSONArray("choices");
            }
        }
        
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        
        JSONObject choice = choices.getJSONObject(0);
        if (choice == null) {
            return null;
        }
        
        return choice.getJSONObject("message");
    }

    public static boolean hasToolCalls(JSONObject response) {
        JSONObject message = getMessageFromResponse(response);
        if (message == null) {
            return false;
        }
        
        return message.containsKey("tool_calls");
    }

    public static List<ToolCall> parseToolCalls(JSONObject response) {
        List<ToolCall> toolCalls = new java.util.ArrayList<>();
        
        JSONObject message = getMessageFromResponse(response);
        if (message == null) {
            return toolCalls;
        }
        
        JSONArray toolCallsArray = message.getJSONArray("tool_calls");
        if (toolCallsArray == null || toolCallsArray.isEmpty()) {
            return toolCalls;
        }
        
        for (int i = 0; i < toolCallsArray.size(); i++) {
            JSONObject toolCallObj = toolCallsArray.getJSONObject(i);
            if (toolCallObj != null) {
                ToolCall toolCall = new ToolCall();
                toolCall.setId(toolCallObj.getString("id"));
                toolCall.setType(toolCallObj.getString("type"));
                
                JSONObject function = toolCallObj.getJSONObject("function");
                if (function != null) {
                    toolCall.setToolName(function.getString("name"));
                    
                    Object argumentsObj = function.get("arguments");
                    if (argumentsObj instanceof JSONObject) {
                        toolCall.setArguments((JSONObject) argumentsObj);
                    } else if (argumentsObj instanceof String) {
                        try {
                            String argumentsStr = (String) argumentsObj;
                            if (!argumentsStr.isEmpty()) {
                                toolCall.setArguments(JSON.parseObject(argumentsStr));
                            } else {
                                toolCall.setArguments(new JSONObject());
                            }
                        } catch (Exception e) {
                            toolCall.setArguments(new JSONObject());
                        }
                    } else {
                        toolCall.setArguments(new JSONObject());
                    }
                }
                
                toolCalls.add(toolCall);
            }
        }
        
        return toolCalls;
    }

    public static String getTextContent(JSONObject response) {
        JSONObject message = getMessageFromResponse(response);
        if (message == null) {
            return null;
        }
        
        Object contentObj = message.get("content");
        if (contentObj == null) {
            return null;
        }
        
        if (contentObj instanceof String) {
            return (String) contentObj;
        }
        
        if (contentObj instanceof JSONArray) {
            JSONArray contentArray = (JSONArray) contentObj;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < contentArray.size(); i++) {
                Object item = contentArray.get(i);
                if (item instanceof String) {
                    sb.append((String) item);
                } else if (item instanceof JSONObject) {
                    JSONObject itemObj = (JSONObject) item;
                    String text = itemObj.getString("text");
                    if (text != null) {
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        }
        
        return contentObj.toString();
    }
}