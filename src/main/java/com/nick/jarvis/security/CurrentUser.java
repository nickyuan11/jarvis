package com.nick.jarvis.security;

/**
 * 获取当前登录用户。
 * 现在还没有登录功能，先固定返回 1。
 * 把这件事集中到一个地方，以后接入登录时只需要改这里，业务代码不用动。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id() {
        return 1L;
    }
}
