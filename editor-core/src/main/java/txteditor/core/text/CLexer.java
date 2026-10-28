package txteditor.core.text;

/** C：预处理行、C 关键字、类型名。 */
class CLexer extends CStyleLexer {
    CLexer() { super(false); }

    @Override void define() {
        keyword("auto", "break", "case", "const", "continue", "default", "do", "else", "enum", "extern",
                "for", "goto", "if", "inline", "register", "restrict", "return", "sizeof", "static",
                "struct", "switch", "typedef", "union", "volatile", "while", "_Alignas", "_Atomic",
                "_Generic", "_Noreturn", "_Static_assert", "_Thread_local");
        type("char", "double", "float", "int", "long", "short", "signed", "unsigned", "void", "_Bool",
                "size_t", "ssize_t", "int8_t", "int16_t", "int32_t", "int64_t", "uint8_t", "uint16_t",
                "uint32_t", "uint64_t", "FILE");
        literal("NULL", "true", "false");
    }
}

/** C++：在 C 之上补类、命名空间、模板与字面量。 */
final class CppLexer extends CLexer {
    @Override void define() {
        super.define();
        keyword("catch", "class", "constexpr", "const_cast", "delete", "dynamic_cast", "explicit",
                "export", "friend", "mutable", "namespace", "new", "noexcept", "operator", "private",
                "protected", "public", "reinterpret_cast", "static_assert", "static_cast", "template",
                "this", "throw", "try", "typeid", "typename", "using", "virtual", "override", "final");
        literal("nullptr");
    }
}
