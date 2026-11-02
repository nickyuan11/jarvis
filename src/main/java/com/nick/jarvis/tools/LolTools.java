package com.nick.jarvis.tools;

import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.nick.jarvis.sports.LolService;

/**
 * 交给大模型使用的英雄联盟赛事工具。赛果必须来自工具，不能凭记忆回答。
 */
@Component
public class LolTools {

    private static final String TEAM_PARAM = """
            战队的英文简称，例如 BLG、T1、GEN、HLE、JDG、TES、AL、WBG、KT、DK、G2。
            用户说中文名或昵称时先换算成简称（如“B站”→BLG）。战队名单每年都会变化，不确定时先问用户。""";

    private final LolService lolService;

    public LolTools(LolService lolService) {
        this.lolService = lolService;
    }

    @Tool(description = "查询某支英雄联盟战队最近已结束的比赛：时间、赛事、比分和胜负。问“BLG 昨天赢了吗”“T1 最近状态怎么样”时使用。")
    public String lolRecentMatches(
            @ToolParam(description = TEAM_PARAM) String team,
            @ToolParam(description = "返回几场，默认 3，最多 10", required = false) Integer count) {
        return safely(() -> lolService.recentMatches(team, clamp(count)));
    }

    @Tool(description = """
            查询英雄联盟正在进行和即将开始的比赛（北京时间）。
            指定战队时返回该战队的赛程；不指定时返回世界赛（Worlds）的赛程。
            问“BLG 下一场什么时候打”“今天世界赛有什么比赛”时使用。""")
    public String lolUpcomingMatches(
            @ToolParam(description = TEAM_PARAM + " 可不填。", required = false) String team,
            @ToolParam(description = "返回几场，默认 5，最多 10", required = false) Integer count) {
        return safely(() -> lolService.upcomingMatches(team, count == null ? 5 : clamp(count)));
    }

    private static int clamp(Integer count) {
        return count == null ? 3 : Math.max(1, Math.min(count, 10));
    }

    private static String safely(Supplier<String> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "查询失败：" + e.getMessage();
        }
    }
}
