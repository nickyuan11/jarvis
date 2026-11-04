package com.nick.jarvis.support;

/** 异常相关的小工具 */
public final class Exceptions {

    private Exceptions() {
    }

    /**
     * 把异常写成一行，同时带上最外层和最里层，例如：
     * MailAuthenticationException <- SocketTimeoutException: Read timed out
     * 真正的原因往往在最里层，只看最外层容易误判。
     */
    public static String describe(Throwable e, int maxLength) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = e.getClass().getSimpleName();
        if (root != e) {
            text += " <- " + root.getClass().getSimpleName();
        }
        if (root.getMessage() != null) {
            text += ": " + root.getMessage();
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
