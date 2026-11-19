package com.haloticket.event_service.search;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Chuẩn hóa văn bản tiếng Việt để so khớp không phân biệt dấu và hoa thường ("Hà Nội" khớp "ha noi").
 * Cùng một phép chuẩn hóa được áp dụng ở hai phía: {@link #fold(String)} cho từ khóa trong Java, và cặp chuỗi
 * {@link #TRANSLATE_FROM}/{@link #TRANSLATE_TO} cho hàm {@code translate()} của SQL, nên không cần extension {@code unaccent}.
 */
public final class VietnameseText {

    /** Các chữ cái có dấu (chữ thường) của tiếng Việt. */
    private static final String ACCENTED_LOWER =
            "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ";

    /** Chuỗi ký tự nguồn cho {@code translate()}: mỗi chữ cái có dấu ở dạng thường và hoa. */
    public static final String TRANSLATE_FROM;

    /** Chuỗi ký tự đích cho {@code translate()}: chữ cái không dấu tương ứng, luôn ở dạng thường; cùng độ dài với nguồn. */
    public static final String TRANSLATE_TO;

    static {
        StringBuilder from = new StringBuilder();
        StringBuilder to = new StringBuilder();
        for (char c : ACCENTED_LOWER.toCharArray()) {
            String plain = fold(String.valueOf(c));
            from.append(c).append(Character.toUpperCase(c));
            to.append(plain).append(plain);
        }
        TRANSLATE_FROM = from.toString();
        TRANSLATE_TO = to.toString();
    }

    private VietnameseText() {
    }

    /**
     * Bỏ dấu, đổi đ thành d và đưa về chữ thường.
     *
     * @param value chuỗi cần chuẩn hóa, có thể {@code null}
     * @return chuỗi đã chuẩn hóa; chuỗi rỗng nếu đầu vào {@code null}
     */
    public static String fold(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        String stripped = decomposed.replaceAll("\\p{M}+", "");
        return stripped.replace('đ', 'd').replace('Đ', 'd').toLowerCase(Locale.ROOT);
    }

    /**
     * Thoát các ký tự đặc biệt của {@code LIKE} (% _ và dấu thoát) để từ khóa do người dùng nhập luôn được hiểu là chữ thường.
     *
     * @param value chuỗi đã chuẩn hóa
     * @return chuỗi an toàn để đặt vào mẫu LIKE với ký tự thoát là dấu gạch chéo ngược
     */
    public static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
