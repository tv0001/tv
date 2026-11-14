package com.tv.player.core;

// 主进程调爬虫进程（:spider）：jar 是配置里的 spider 字段，site 是 {key, cls, ext}，args 是 JSON 数组；返回爬虫的原始 JSON
interface ISpiderService {
    String call(String jar, String site, String method, String args);
}
