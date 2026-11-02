package com.nick.jarvis.sports;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.nick.jarvis.support.JarvisTime;
import com.nick.jarvis.support.MapPath;
import com.nick.jarvis.support.TextCache;

/**
 * NBA 数据，来自 ESPN 的公开接口（无需 API Key）。
 *
 * 两个原则：
 * 1. 只把大模型需要的信息整理成简短文本返回。原始 JSON 动辄几百 KB，直接塞给模型既费 token 又容易让它看错。
 * 2. 结果缓存在 Redis（TextCache）：打完的比赛结果不会变，没必要每次都请求 ESPN，既快，又能避免被对方限流。
 */
@Service
public class NbaService {

    private static final Logger log = LoggerFactory.getLogger(NbaService.class);
    private static final String BASE_URL = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba";
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RestClient restClient;
    private final TextCache cache;
    private final JarvisTime time;

    public NbaService(TextCache cache, JarvisTime time) {
        // 第三方接口一定要设超时，否则对方卡住时，我们的请求线程也会一直卡着
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(factory)
                // 不带 User-Agent 时，Java 默认发送“Java/21”，容易被对方的防护系统当成爬虫拦截（403）。
                // 这里如实表明自己是谁，而不是伪装成浏览器
                .defaultHeader(HttpHeaders.USER_AGENT, "Jarvis/0.1 (personal assistant)")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
        this.cache = cache;
        this.time = time;
    }

    /** 某支球队最近已经结束的比赛 */
    public String recentGames(String team, int count) {
        String abbr = normalize(team);
        return cached("recent:" + abbr + ":" + count, Duration.ofMinutes(5), () -> {
            List<Map<?, ?>> finished = events(abbr).stream()
                    .filter(e -> MapPath.bool(e, "competitions", 0, "status", "type", "completed"))
                    .toList();
            if (finished.isEmpty()) {
                return "没有找到 " + abbr.toUpperCase() + " 本赛季已结束的比赛";
            }
            StringBuilder sb = new StringBuilder(abbr.toUpperCase() + " 最近已结束的比赛（北京时间）：\n");
            finished.subList(Math.max(0, finished.size() - count), finished.size())
                    .forEach(e -> sb.append(describeGame(e, abbr)).append('\n'));
            return sb.toString();
        });
    }

    /** 某支球队接下来的比赛 */
    public String upcomingGames(String team, int count) {
        String abbr = normalize(team);
        return cached("upcoming:" + abbr + ":" + count, Duration.ofMinutes(30), () -> {
            List<Map<?, ?>> upcoming = events(abbr).stream()
                    .filter(e -> !MapPath.bool(e, "competitions", 0, "status", "type", "completed"))
                    .limit(count)
                    .toList();
            if (upcoming.isEmpty()) {
                return "没有找到 " + abbr.toUpperCase() + " 接下来的比赛";
            }
            StringBuilder sb = new StringBuilder(abbr.toUpperCase() + " 接下来的比赛（北京时间）：\n");
            upcoming.forEach(e -> sb.append(describeGame(e, abbr)).append('\n'));
            return sb.toString();
        });
    }

    /** 一场比赛的球员数据。player 为空时返回双方所有上场球员 */
    public String boxScore(String gameId, String player) {
        String all = cached("box:" + gameId, Duration.ofHours(1), () -> {
            Map<?, ?> summary = restClient.get().uri("/summary?event={id}", gameId).retrieve().body(Map.class);
            StringBuilder sb = new StringBuilder();
            for (Object teamBlock : MapPath.list(summary, "boxscore", "players")) {
                String teamAbbr = MapPath.string(teamBlock, "team", "abbreviation");
                Object stats = MapPath.get(teamBlock, "statistics", 0);
                List<?> labels = MapPath.list(stats, "labels");
                if (labels.isEmpty()) {
                    labels = MapPath.list(stats, "names");
                }
                for (Object athlete : MapPath.list(stats, "athletes")) {
                    String name = MapPath.string(athlete, "athlete", "displayName");
                    if (name == null) {
                        continue;
                    }
                    // 没上场的球员也要列出来并注明原因，否则大模型分不清“没上场”和“不在这支球队”
                    List<?> values = MapPath.list(athlete, "stats");
                    if (MapPath.bool(athlete, "didNotPlay") || values.isEmpty()) {
                        String reason = MapPath.string(athlete, "reason");
                        sb.append(teamAbbr).append(" ").append(name).append(": 未出场")
                                .append(reason == null || reason.isBlank() ? "" : "（原因：" + reason + "）")
                                .append('\n');
                        continue;
                    }
                    sb.append(teamAbbr).append(" ").append(name).append(": ");
                    for (int i = 0; i < Math.min(labels.size(), values.size()); i++) {
                        sb.append(labels.get(i)).append(' ').append(values.get(i)).append(i < values.size() - 1 ? ", " : "");
                    }
                    sb.append('\n');
                }
            }
            return sb.isEmpty() ? "没有找到比赛 " + gameId + " 的球员数据" : sb.toString();
        });

        if (player == null || player.isBlank()) {
            return all;
        }
        String keyword = player.strip().toLowerCase(Locale.ROOT);
        String matched = all.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).contains(keyword))
                .reduce("", (a, b) -> a + b + "\n");
        return matched.isEmpty()
                ? "比赛 " + gameId + " 的数据里没有球员 " + player + "。可能他因伤病等原因不在本场大名单中，"
                        + "这不代表他不属于这支球队"
                : matched;
    }

    // ------------------------------------------------------------------

    private List<Map<?, ?>> events(String abbr) {
        Map<?, ?> schedule = restClient.get().uri("/teams/{team}/schedule", abbr).retrieve().body(Map.class);
        List<Map<?, ?>> events = new ArrayList<>();
        for (Object e : MapPath.list(schedule, "events")) {
            if (e instanceof Map<?, ?> map) {
                events.add(map);
            }
        }
        return events;
    }

    /** 例如：2026-10-31 07:30  PHI 118 : 112 BOS（胜，主场） 比赛ID 401810001 */
    private String describeGame(Map<?, ?> event, String abbr) {
        Object competition = MapPath.get(event, "competitions", 0);
        Object us = null;
        Object them = null;
        for (Object c : MapPath.list(competition, "competitors")) {
            String a = MapPath.string(c, "team", "abbreviation");
            if (a != null && a.equalsIgnoreCase(abbr)) {
                us = c;
            } else {
                them = c;
            }
        }
        String when = toLocal(MapPath.string(event, "date"));
        String ourName = MapPath.string(us, "team", "abbreviation");
        String theirName = MapPath.string(them, "team", "abbreviation");
        String venue = "home".equals(MapPath.string(us, "homeAway")) ? "主场" : "客场";
        String id = MapPath.string(event, "id");

        String ourScore = MapPath.string(us, "score", "displayValue");
        if (ourScore == null) {
            return when + "  " + ourName + " vs " + theirName + "（" + venue + "） 比赛ID " + id;
        }
        String theirScore = MapPath.string(them, "score", "displayValue");
        String result = MapPath.bool(us, "winner") ? "胜" : "负";
        return when + "  " + ourName + " " + ourScore + " : " + theirScore + " " + theirName
                + "（" + result + "，" + venue + "） 比赛ID " + id;
    }

    private String toLocal(String isoDate) {
        if (isoDate == null) {
            return "时间未知";
        }
        try {
            // ESPN 的时间是 UTC，形如 2026-10-30T23:30Z，统一换算成北京时间
            return OffsetDateTime.parse(isoDate).atZoneSameInstant(time.zone()).format(DISPLAY);
        } catch (Exception e) {
            return isoDate;
        }
    }

    private static String normalize(String team) {
        if (team == null || team.isBlank()) {
            throw new IllegalArgumentException("需要球队缩写，例如 phi");
        }
        return team.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 缓存 key 带上版本号 v2：以后改了数据格式，把版本号加一，旧缓存就不会再被读到，到期自动清掉。
     * ESPN 请求失败时转成统一的提示，交给工具层告诉大模型。
     */
    private String cached(String key, Duration ttl, Supplier<String> loader) {
        return cache.get("nba:v2:" + key, ttl, () -> {
            try {
                return loader.get();
            } catch (RestClientException e) {
                log.warn("ESPN request failed for {}", key, e);
                throw new IllegalStateException("暂时无法获取 NBA 数据，请稍后再试");
            }
        });
    }
}
