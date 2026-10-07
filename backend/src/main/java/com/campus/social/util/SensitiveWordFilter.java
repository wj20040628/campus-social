package com.campus.social.util;

import com.campus.social.entity.SensitiveWord;
import com.campus.social.mapper.SensitiveWordMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词 DFA(确定有限自动机)
 * 启动时从 sensitive_word 表加载,定时刷新
 *
 * level 语义:
 *   1 替换:命中词替换为 *
 *   2 拒发:命中即整条内容拒发
 *
 * 返回值:
 *   check 命中 → 拒发
 *   replace 命中 → 返回替换后文本
 */
@Slf4j
@Component
public class SensitiveWordFilter {

    /** 最大词长(限制 trie 深度,防恶意超长词) */
    private static final int MAX_WORD_LEN = 64;

    private final SensitiveWordMapper mapper;

    /**
     * 根节点。next 用 Map 按字符(0..65535)分桶,支持中文/全角等任意 Unicode,
     * 不再假设 a-z ASCII。level=null 表示普通节点,非 null 表示终止(1替换/2拒发)。
     */
    private static class TrieNode {
        final Map<Character, TrieNode> next = new HashMap<>();
        Integer level; // null=非终止, 1=替换, 2=拒发
        String originalWord; // 终止节点存原文,matchedLevel1Words 取词用
    }

    private TrieNode root = new TrieNode();

    /** 词表原文(lower-case 形式 → 原词),matchedLevel1Words 切片回查原文 */
    private final Map<String, String> level1ByLower = new HashMap<>();

    public SensitiveWordFilter(SensitiveWordMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        load();
        log.info("SensitiveWordFilter init, words loaded");
    }

    /** 全量重建 DFA(由定时任务调用) */
    public synchronized void load() {
        List<SensitiveWord> words = mapper.selectList(null);
        TrieNode r = new TrieNode();
        r.level = null;
        for (SensitiveWord w : words) {
            if (w.getWord() == null || w.getWord().isBlank()) continue;
            insert(r, w.getWord(), w.getLevel());
        }
        this.root = r;
        log.info("SensitiveWordFilter reload, total words={}", words.size());
    }

    private void insert(TrieNode root, String word, int level) {
        TrieNode cur = root;
        for (int i = 0; i < word.length() && i < MAX_WORD_LEN; i++) {
            // 与 match/findMatchEnd 的查询侧保持一致:统一存 toLowerCase(char)
            char c = Character.toLowerCase(word.charAt(i));
            cur.next.computeIfAbsent(c, k -> new TrieNode());
            cur = cur.next.get(c);
        }
        cur.level = level;
        cur.originalWord = word;
        if (level == 1) {
            level1ByLower.put(word.toLowerCase(), word);
        }
    }

    /** 命中即返 true(level=2) */
    public boolean containsBlock(String text) {
        return scan(text, 2);
    }

    /** 是否命中了 level=1 的词(需替换并送人工审核) */
    public boolean containsReplace(String text) {
        return scan(text, 1);
    }

    /**
     * 命中的 level-1 敏感词原文列表(送审时记录,审核台展示用)。
     * 与 replaceAll 同位扫描;每个命中取词表原文(非替换后切片),LinkedHashMap 去重保序。
     * 注意:content 入库前会被 replaceAll 成 *,故必须对**原文**调用本方法。
     */
    public List<String> matchedLevel1Words(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        char[] arr = text.toCharArray();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (int i = 0; i < arr.length; i++) {
            if (match(arr, i, 1)) {
                int end = findMatchEnd(arr, i, 1);
                if (end > i) {
                    String slice = toLowerSlice(arr, i, end);
                    String orig = level1ByLower.getOrDefault(slice, slice);
                    seen.putIfAbsent(orig, Boolean.TRUE);
                }
                i = end - 1;
            }
        }
        return List.copyOf(seen.keySet());
    }

    /** 取 [i, end) 切片并 lower-case,用于回查词表原文 */
    private String toLowerSlice(char[] arr, int i, int end) {
        StringBuilder sb = new StringBuilder(end - i);
        for (int k = i; k < end; k++) {
            sb.append(Character.toLowerCase(arr[k]));
        }
        return sb.toString();
    }

    /** 命中 level=1 的词替换为 * */
    public String replaceAll(String text) {
        if (text == null) return null;
        char[] arr = text.toCharArray();
        for (int i = 0; i < arr.length; i++) {
            if (match(arr, i, 1)) {
                // 找到终止节点时,把 [i, i+len) 全替换 *
                int end = findMatchEnd(arr, i, 1);
                for (int j = i; j < Math.min(end, arr.length); j++) {
                    arr[j] = '*';
                }
                i = end - 1;
            }
        }
        return new String(arr);
    }

    private boolean match(char[] text, int start, int level) {
        TrieNode cur = root;
        for (int i = start; i < text.length; i++) {
            char c = Character.toLowerCase(text[i]);
            cur = cur.next.get(c);
            if (cur == null) break;
            if (cur.level != null && cur.level == level) return true;
        }
        return false;
    }

    /** 找到从 start 匹配 level 词的结尾(返回 end 下标,exclusive) */
    private int findMatchEnd(char[] text, int start, int level) {
        TrieNode cur = root;
        int end = start;
        for (int i = start; i < text.length; i++) {
            char c = Character.toLowerCase(text[i]);
            cur = cur.next.get(c);
            if (cur == null) break;
            end = i + 1;
            if (cur.level != null && cur.level == level) break;
        }
        return end;
    }

    private boolean scan(String text, int level) {
        if (text == null) return false;
        char[] arr = text.toCharArray();
        // DFA 匹配:必须对每个起点都试一次(词可能从任意位置出现),
        // 不能只从 0 走一遍 —— 否则"这里有 测试违禁词"因首字不在 trie 而漏报。
        for (int i = 0; i < arr.length; i++) {
            if (match(arr, i, level)) return true;
        }
        return false;
    }
}
