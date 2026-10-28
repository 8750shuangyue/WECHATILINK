package com.example.demo.audit;

public final class AuditAction {

    public static final String AUTH_LOGIN = "AUTH_LOGIN";
    public static final String AUTH_REGISTER = "AUTH_REGISTER";
    public static final String AUTH_LOGOUT = "AUTH_LOGOUT";

    public static final String KB_UPLOAD = "KB_UPLOAD";
    public static final String KB_DELETE = "KB_DELETE";

    public static final String PRODUCT_PUBLISH = "PRODUCT_PUBLISH";
    public static final String PRODUCT_DELETE = "PRODUCT_DELETE";

    public static final String COMMUNITY_POST_DELETE = "COMMUNITY_POST_DELETE";
    public static final String CARE_TARGET_DELETE = "CARE_TARGET_DELETE";

    public static final String UPLOAD_FILE_PROCESS = "UPLOAD_FILE_PROCESS";
    public static final String UPLOAD_FILE_QA = "UPLOAD_FILE_QA";
    public static final String UPLOAD_ASR = "UPLOAD_ASR";

    public static final String VISION_PLANT = "VISION_PLANT";
    public static final String VISION_PET = "VISION_PET";
    public static final String VISION_DISEASE = "VISION_DISEASE";
    public static final String VISION_CARE_IDENTIFY = "VISION_CARE_IDENTIFY";

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
    public static final String DENIED = "DENIED";
    public static final String BLOCKED = "BLOCKED";

    private AuditAction() {
    }
}
