package com.example.demo.utils;

public class
JsonUtils {

    private JsonUtils() {
    }

    public static String unescapeJson(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); i += 2; continue;
                    case 't': sb.append('\t'); i += 2; continue;
                    case 'r': sb.append('\r'); i += 2; continue;
                    case '"': sb.append('\"'); i += 2; continue;
                    case '\\': sb.append('\\'); i += 2; continue;
                    case 'b': sb.append('\b'); i += 2; continue;
                    case 'f': sb.append('\f'); i += 2; continue;
                    default: sb.append(c); i++; continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}