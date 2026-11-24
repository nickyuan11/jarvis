package com.nick.jarvis.sports;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import com.nick.jarvis.support.MapPath;
import com.nick.jarvis.support.TextCache;

/**
 * 英雄联盟赛事数据，来自 PandaScore（免费套餐包含赛程和比赛结果）。
 *
 * 和 NbaService 一样：只把需要的信息整理成简短文本交给大模型，结果缓存在 Redis。
 */
@Service
public class LolService {

    private static final Logger log = LoggerFactory.getLogger(LolService.class);
    private static final String BASE_URL = "https://api.pandascore.co";
    /** 不指定队伍时默认看世界赛（PandaScore 里 Worlds 联赛的 id） */
    private static final int WORLDS_LEAGUE_ID = 297;
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RestClient restClient;
    private final TextCache cache;
    private final boolean configured;

    public LolService(TextCache cache,
                      @Value("${jarvis.pandascore.token:}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(factory)
                // token 放在请求头里，而不是拼在 URL 上：URL 容易出现在日志、监控和报错信息里
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
        this.cache = cache;
        this.configured = token != null && !token.isBlank();
    }

    /** 某支队伍最近已结束的比赛 */
    public String recentMatches(String team, int count, ZoneId zone) {
        requireConfigured();
        String acronym = normalize(team);
        if (acronym == null) {
            throw new IllegalArgumentException("需要队伍简称，例如 BLG、T1");
        }
        return cache.get("lol:v2:recent:" + zone + ":" + acronym + ":" + count, Duration.ofMinutes(5), () -> {
            List<Map<?, ?>> matches = fetch("/lol/matches/past", acronym, count);
            if (matches.isEmpty()) {
                return "没有找到 " + acronym + " 最近的比赛，请确认队伍简称是否正确";
            }
            StringBuilder sb = new StringBuilder(acronym + " 最近的比赛（" + zone + "，最新的在前）：\n");
            matches.forEach(m -> sb.append(describe(m, zone)).append('\n'));
            return sb.toString();
        });
    }

    /** 正在进行和即将开始的比赛；team 为空时返回世界赛的赛程 */
    public String upcomingMatches(String team, int count, ZoneId zone) {
        requireConfigured();
        String acronym = normalize(team);
        String key = acronym == null ? "worlds" : acronym;
        return cache.get("lol:v2:upcoming:" + zone + ":" + key + ":" + count, Duration.ofMinutes(5), () -> {
            List<Map<?, ?>> running = fetch("/lol/matches/running", acronym, count);
            List<Map<?, ?>> upcoming = fetch("/lol/matches/upcoming", acronym, count);
            String title = acronym == null ? "世界赛" : acronym;
            if (running.isEmpty() && upcoming.isEmpty()) {
                return "没有找到 " + title + " 正在进行或即将开始的比赛";
            }
            StringBuilder sb = new StringBuilder();
            if (!running.isEmpty()) {
                sb.append(title).append(" 正在进行的比赛（").append(zone).append("）：\n");
                running.forEach(m -> sb.append(describe(m, zone)).append('\n'));
            }
            if (!upcoming.isEmpty()) {
                sb.append(title).append(" 接下来的比赛（").append(zone).append("）：\n");
                upcoming.forEach(m -> sb.append(describe(m, zone)).append('\n'));
            }
            return sb.toString();
        });
    }

    // ------------------------------------------------------------------

    private List<Map<?, ?>> fetch(String path, String acronym, int count) {
        try {
            List<?> body = restClient.get()
                    .uri(builder -> {
                        UriBuilder b = builder.path(path).queryParam("per_page", count);
                        if (acronym != null) {
                            // 比赛名形如“Round 5: HLE vs BLG”，按队伍简称搜索
                            b.queryParam("search[name]", acronym);
                        } else {
                            b.queryParam("filter[league_id]", WORLDS_LEAGUE_ID);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .body(List.class);
            List<Map<?, ?>> matches = new ArrayList<>();
            if (body != null) {
                for (Object m : body) {
                    if (m instanceof Map<?, ?> map && (acronym == null || involves(map, acronym))) {
                        matches.add(map);
                    }
                }
            }
            return matches;
        } catch (RestClientException e) {
            log.warn("PandaScore request failed: {}", path, e);
            throw new IllegalStateException("暂时无法获取英雄联盟赛事数据，请稍后再试");
        }
    }

    /**
     * 按名字搜索是模糊匹配，“T1”也可能匹配到名字里碰巧含 T1 的其他比赛，
     * 所以再核对一遍：参赛队伍里确实有这个简称才算。
     */
    private boolean involves(Map<?, ?> match, String acronym) {
        for (Object o : MapPath.list(match, "opponents")) {
            if (acronym.equalsIgnoreCase(MapPath.string(o, "opponent", "acronym"))) {
                return true;
            }
        }
        return false;
    }

    /** 例如：2026-11-01 06:04 Worlds 2026 Group Stage | HLE 0 : 2 BLG（BLG 胜，BO3） */
    private String describe(Map<?, ?> match, ZoneId zone) {
        // 队伍 id → 简称，用来把比分对应到队伍上
        Map<String, String> names = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (Object o : MapPath.list(match, "opponents")) {
            String id = MapPath.string(o, "opponent", "id");
            String acronym = MapPath.string(o, "opponent", "acronym");
            String name = acronym != null ? acronym : MapPath.string(o, "opponent", "name");
            names.put(id, name);
            order.add(id);
        }
        String when = toLocal(MapPath.string(match, "begin_at") != null
                ? MapPath.string(match, "begin_at") : MapPath.string(match, "scheduled_at"), zone);
        String event = String.join(" ",
                nonNull(MapPath.string(match, "league", "name")),
                nonNull(MapPath.string(match, "serie", "full_name")),
                nonNull(MapPath.string(match, "tournament", "name"))).strip();
        String bestOf = MapPath.get(match, "number_of_games") == null ? "" : "BO" + MapPath.string(match, "number_of_games");

        if (order.size() < 2) {
            return when + " " + event + " | " + nonNull(MapPath.string(match, "name")) + "（对阵待定）";
        }
        String a = names.get(order.get(0));
        String b = names.get(order.get(1));
        String status = MapPath.string(match, "status");
        if ("not_started".equals(status)) {
            return when + " " + event + " | " + a + " vs " + b + "（" + bestOf + "）";
        }

        Map<String, String> scores = new HashMap<>();
        for (Object r : MapPath.list(match, "results")) {
            scores.put(MapPath.string(r, "team_id"), MapPath.string(r, "score"));
        }
        String score = a + " " + nonNull(scores.get(order.get(0))) + " : " + nonNull(scores.get(order.get(1))) + " " + b;
        String result;
        if ("running".equals(status)) {
            result = "进行中";
        } else {
            String winner = names.get(MapPath.string(match, "winner_id"));
            result = winner == null ? "已结束" : winner + " 胜";
        }
        return when + " " + event + " | " + score + "（" + result + "，" + bestOf + "）";
    }

    private String toLocal(String isoDate, ZoneId zone) {
        if (isoDate == null) {
            return "时间待定";
        }
        try {
            return OffsetDateTime.parse(isoDate).atZoneSameInstant(zone).format(DISPLAY);
        } catch (Exception e) {
            return isoDate;
        }
    }

    private void requireConfigured() {
        if (!configured) {
            throw new IllegalStateException("还没有配置 PandaScore 的 token（jarvis.pandascore.token），暂时无法查询英雄联盟赛事");
        }
    }

    private static String normalize(String team) {
        return team == null || team.isBlank() ? null : team.strip().toUpperCase();
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
