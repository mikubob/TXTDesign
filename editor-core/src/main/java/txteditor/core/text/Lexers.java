package txteditor.core.text;

/** 按语言取词法器；PLAIN 什么都不产出。 */
public final class Lexers {
    private static final Lexer PLAIN = new Lexer() {
        @Override public byte initialState() { return 0; }
        @Override public byte lexLine(CharSequence text, int start, int end, byte state, TokenSink out) { return 0; }
    };
    private static final Lexer JAVA = new JavaLexer();
    private static final Lexer C = new CLexer();
    private static final Lexer CPP = new CppLexer();
    private static final Lexer JSON = new JsonLexer();
    private static final Lexer XML = new XmlLexer();
    private static final Lexer MARKDOWN = new MarkdownLexer();

    public static Lexer of(LanguageId id) {
        switch (id) {
            case JAVA: return JAVA;
            case C: return C;
            case CPP: return CPP;
            case JSON: return JSON;
            case XML: return XML;
            case MARKDOWN: return MARKDOWN;
            default: return PLAIN;
        }
    }

    private Lexers() { }
}
