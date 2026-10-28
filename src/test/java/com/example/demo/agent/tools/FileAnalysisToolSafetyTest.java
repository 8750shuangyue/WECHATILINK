package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.core.FileParserService;
import com.example.demo.core.UploadPathProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileAnalysisToolSafetyTest {

    @TempDir
    Path tempDir;

    private FileAnalysisTool tool() throws Exception {
        UploadPathProvider provider = new UploadPathProvider(
                tempDir.toString(), tempDir.resolve("tmp").toString());
        FileParserService parser = mock(FileParserService.class);
        when(parser.parseBytes(any(), eq("ok.txt"))).thenReturn("hello");
        return new FileAnalysisTool(parser, provider);
    }

    private ToolResult<?> execute(FileAnalysisTool tool, String fileUrl) {
        JSONObject params = new JSONObject();
        params.put("fileUrl", fileUrl);
        params.put("fileName", "ok.txt");
        return tool.execute(params);
    }

    @Test
    void allowsFileInsideUploads() throws Exception {
        Files.writeString(tempDir.resolve("ok.txt"), "hello", StandardCharsets.UTF_8);

        assertTrue(execute(tool(), "/uploads/ok.txt").isSuccess());
    }

    @Test
    void rejectsAbsoluteLocalPath() throws Exception {
        assertFalse(execute(tool(), "/etc/passwd").isSuccess());
        assertFalse(execute(tool(), "C:\\Windows\\win.ini").isSuccess());
    }

    @Test
    void rejectsFileUriOutsideUploads() throws Exception {
        assertFalse(execute(tool(), "file:///etc/passwd").isSuccess());
    }

    @Test
    void rejectsDataUri() throws Exception {
        assertFalse(execute(tool(), "data:text/plain;base64,aGVsbG8=").isSuccess());
    }

    @Test
    void rejectsLoopbackAndMetadataAddresses() throws Exception {
        assertFalse(execute(tool(), "http://127.0.0.1:9/x.txt").isSuccess());
        assertFalse(execute(tool(), "http://169.254.169.254/latest/meta-data").isSuccess());
        assertFalse(execute(tool(), "http://10.0.0.1/x.txt").isSuccess());
    }
}
