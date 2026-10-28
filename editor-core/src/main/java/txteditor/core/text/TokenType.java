package txteditor.core.text;

/** Token 分类；存为 byte，平台层只做类型到颜色的映射。 */
public final class TokenType {
    public static final byte PLAIN = 0, KEYWORD = 1, TYPE = 2, LITERAL = 3, NUMBER = 4, STRING = 5,
            CHAR = 6, COMMENT = 7, ANNOTATION = 8, PREPROCESSOR = 9, OPERATOR = 10, IDENTIFIER = 11,
            TAG = 12, ATTRIBUTE = 13, HEADING = 14, LINK = 15, CODE = 16, EMPHASIS = 17,
            LIST_MARKER = 18, QUOTE = 19, TABLE = 20, STRONG = 21, STRIKE = 22, MARKER = 23;
    public static final int COUNT = 24;

    private TokenType() { }
}
