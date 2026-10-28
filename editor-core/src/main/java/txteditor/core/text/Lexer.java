package txteditor.core.text;

/** 逐行词法接口：state 是行首跨行状态（注释、字符串、围栏），返回值是行尾状态。 */
public interface Lexer {
    byte initialState();

    byte lexLine(CharSequence text, int start, int end, byte state, TokenSink out);
}
