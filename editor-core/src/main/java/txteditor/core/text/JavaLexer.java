package txteditor.core.text;

/** Java：C 风格扫描 + 注解 + 文本块。 */
final class JavaLexer extends CStyleLexer {
    JavaLexer() { super(true); }

    @Override void define() {
        keyword("abstract", "assert", "break", "case", "catch", "class", "const", "continue", "default",
                "do", "else", "enum", "extends", "final", "finally", "for", "goto", "if", "implements",
                "import", "instanceof", "interface", "native", "new", "package", "private", "protected",
                "public", "return", "sealed", "static", "strictfp", "super", "switch", "synchronized",
                "this", "throw", "throws", "transient", "try", "var", "volatile", "while", "yield",
                "record", "permits", "non-sealed");
        type("boolean", "byte", "char", "double", "float", "int", "long", "short", "void");
        literal("true", "false", "null");
    }
}
