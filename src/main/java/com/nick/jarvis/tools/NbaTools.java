package com.nick.jarvis.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.nick.jarvis.sports.NbaService;

/**
 * 交给大模型使用的 NBA 查询工具。比分、数据必须来自工具，不能让模型凭自己的知识回答。
 */
@Component
public class NbaTools {

    private static final String TEAM_PARAM = """
            NBA 球队的 ESPN 缩写（小写），例如 phi=费城76人、lal=湖人、bos=凯尔特人、gs=勇士、ny=尼克斯、sa=马刺、okc=雷霆。
            注意：球员经常转会，你记忆中某位球员所在的球队可能已经过时。
            用户问某位球员却没说球队时，优先根据对话上下文判断；仍不确定就先问用户他现在在哪支球队，不要猜。""";

    private final NbaService nbaService;

    public NbaTools(NbaService nbaService) {
        this.nbaService = nbaService;
    }

    @Tool(description = "查询某支 NBA 球队最近已经结束的比赛：时间、比分、胜负和比赛ID。问“XX 昨天赢了吗”“最近战绩”时使用。")
    public String nbaRecentGames(
            @ToolParam(description = TEAM_PARAM) String team,
            @ToolParam(description = "返回几场，默认 3，最多 10", required = false) Integer count, ToolContext context) {
        return safely(() -> nbaService.recentGames(team, clamp(count), ToolContextKeys.zone(context)));
    }

    @Tool(description = "查询某支 NBA 球队接下来的比赛时间（用户当地时间）。问“XX 下一场什么时候打”时使用。")
    public String nbaUpcomingGames(
            @ToolParam(description = TEAM_PARAM) String team,
            @ToolParam(description = "返回几场，默认 3，最多 10", required = false) Integer count, ToolContext context) {
        return safely(() -> nbaService.upcomingGames(team, clamp(count), ToolContextKeys.zone(context)));
    }

    @Tool(description = """
            查询一场 NBA 比赛的球员个人数据（得分、篮板、助攻、上场时间、命中率等）。
            比赛ID 从 nbaRecentGames 的结果里获取。问“詹姆斯昨天拿了多少分”时，先查最近比赛，再用本工具。""")
    public String nbaBoxScore(
            @ToolParam(description = "比赛ID，例如 401810001") String gameId,
            @ToolParam(description = "球员英文名或其中一部分，例如 LeBron James 或 James；不填则返回所有上场球员",
                    required = false) String player) {
        return safely(() -> nbaService.boxScore(gameId, player));
    }

    private static int clamp(Integer count) {
        return count == null ? 3 : Math.max(1, Math.min(count, 10));
    }

    private static String safely(java.util.function.Supplier<String> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "查询失败：" + e.getMessage();
        }
    }
}
