package com.example.demo.router;

import com.alibaba.fastjson2.JSON;

public class AiRouteResult {

    private String routeType;
    private String content;
    private boolean needTts;
    private String style;

    public AiRouteResult() {
    }

    public AiRouteResult(MessageRouter.RouteType routeType, String content, boolean needTts) {
        this.routeType = routeType.name();
        this.content = content;
        this.needTts = needTts;
    }

    public AiRouteResult(MessageRouter.RouteType routeType, String content, boolean needTts, String style) {
        this.routeType = routeType.name();
        this.content = content;
        this.needTts = needTts;
        this.style = style;
    }

    public String getRouteType() {
        return routeType;
    }

    public void setRouteType(String routeType) {
        this.routeType = routeType;
    }

    public void setRouteType(MessageRouter.RouteType routeType) {
        this.routeType = routeType.name();
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isNeedTts() {
        return needTts;
    }

    public void setNeedTts(boolean needTts) {
        this.needTts = needTts;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public static AiRouteResult fromJson(String json) {
        return JSON.parseObject(json, AiRouteResult.class);
    }

    public String toJson() {
        return JSON.toJSONString(this);
    }
}
