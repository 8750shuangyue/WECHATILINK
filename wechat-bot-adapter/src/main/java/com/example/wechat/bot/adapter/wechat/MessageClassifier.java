package com.example.wechat.bot.adapter.wechat;

import com.example.wechat.bot.core.model.dto.FileContent;
import com.example.wechat.bot.core.model.dto.UnifiedContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 消息分类器——将原始 {@link WeixinMessage} 中的信息全部提取为 {@link UnifiedContext}，
 * 不做任何业务判断。提取内容包括：文字（text_item + voice_item ASR）、图片字节、文件内容。
 */
@Component
public class MessageClassifier {

    private static final Logger log = LoggerFactory.getLogger(MessageClassifier.class);

    private final WeChatClientService clientService;

    public MessageClassifier(WeChatClientService clientService) {
        this.clientService = clientService;
    }

    /**
     * 对原始微信消息进行分类，返回结构化的 {@link UnifiedContext}。
     */
    public UnifiedContext classify(WeixinMessage message) {
        String userId = message.getFrom_user_id();
        List<MessageItem> items = message.getItem_list();

        if (items == null || items.isEmpty()) {
            return new UnifiedContext(userId, null, null, null);
        }

        String text = extractText(items);
        byte[] imageBytes = downloadImageIfExists(items);
        FileContent fileContent = downloadFileIfExists(items);

        return new UnifiedContext(userId, text, imageBytes, fileContent);
    }

    // ---------- 文本提取 ----------

    /**
     * 从消息项列表中提取文字内容（text_item + voice_item ASR）。
     */
    private static String extractText(List<MessageItem> items) {
        StringBuilder builder = new StringBuilder();
        for (MessageItem item : items) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                String t = item.getText_item().getText().trim();
                if (!t.isEmpty()) {
                    if (!builder.isEmpty()) builder.append('\n');
                    builder.append(t);
                }
            }
            if (item.getVoice_item() != null && item.getVoice_item().getText() != null) {
                String vt = item.getVoice_item().getText().trim();
                if (!vt.isEmpty()) {
                    if (!builder.isEmpty()) builder.append('\n');
                    builder.append(vt);
                }
            }
        }
        String result = builder.toString().trim();
        return result.isEmpty() ? null : result;
    }

    // ---------- 图片下载 ----------

    private byte[] downloadImageIfExists(List<MessageItem> items) {
        for (MessageItem item : items) {
            if (item.getImage_item() != null) {
                return clientService.downloadImage(item);
            }
        }
        return null;
    }

    // ---------- 文件下载 ----------

    private FileContent downloadFileIfExists(List<MessageItem> items) {
        for (MessageItem item : items) {
            if (item.getFile_item() != null) {
                String fileName = item.getFile_item().getFile_name();
                byte[] bytes = clientService.downloadFile(item);
                if (bytes != null && bytes.length > 0) {
                    long fileSize = bytes.length;
                    try {
                        String lenStr = item.getFile_item().getLen();
                        if (lenStr != null && !lenStr.isBlank()) {
                            fileSize = Long.parseLong(lenStr);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    return new FileContent(fileName, bytes, fileSize);
                }
            }
        }
        return null;
    }
}
