package com.example.demo.config;

import com.example.demo.aicare.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一错误返回：所有未捕获异常都落成 {code, success:false, message, data:null}。
 * 不把异常堆栈或内部细节返回给客户端。
 */
@RestControllerAdvice
public class GlobalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Result.error(400, e.getMessage() == null ? "请求参数不合法" : e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Result<Object>> malformedRequest(Exception e) {
        return ResponseEntity.badRequest().body(Result.error(400, "请求格式不正确"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Object>> status(ResponseStatusException e) {
        int code = e.getStatusCode().value();
        String message = e.getReason() == null ? "请求失败" : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Result.error(code, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> unexpected(Exception e) {
        log.error("Unhandled API exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "服务器内部错误"));
    }
}
