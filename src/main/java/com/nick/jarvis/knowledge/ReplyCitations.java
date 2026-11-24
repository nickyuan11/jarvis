package com.nick.jarvis.knowledge;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 一次回答共用一份编号表；并行工具调用也只能给同一出处分配一个编号。 */
public class ReplyCitations {

    public record Citation(int number, long documentId, SourceRange range) {
    }

    private record Source(long documentId, int chunkIndex) {
    }

    private static final Pattern MARKER = Pattern.compile("\\[([1-9][0-9]{0,8})\\]");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Citation>> LIST_TYPE = new TypeReference<>() {};
    private final Map<Source, Citation> sources = new LinkedHashMap<>();

    public synchronized int number(KnowledgeSearchService.Hit hit) {
        return sources.computeIfAbsent(new Source(hit.documentId(), hit.chunkIndex()),
                key -> new Citation(sources.size() + 1, key.documentId(), hit.range())).number();
    }

    public synchronized List<Citation> usedIn(String reply) {
        Set<Integer> used = new LinkedHashSet<>();
        var markers = MARKER.matcher(reply);
        while (markers.find()) {
            used.add(Integer.parseInt(markers.group(1)));
        }
        return sources.values().stream().filter(c -> c.range() != null && used.contains(c.number())).toList();
    }

    public static String encode(List<Citation> citations) {
        return citations.isEmpty() ? null : JSON.writeValueAsString(citations);
    }

    public static List<Citation> decode(String json) {
        return json == null ? List.of() : JSON.readValue(json, LIST_TYPE);
    }

    public static List<Integer> numbers(String json) {
        return decode(json).stream().map(Citation::number).toList();
    }
}
