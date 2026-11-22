package com.actionloop.api.ai;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地确定性规则抽取（指导书 14.2 兜底）：正则日期 + 动作词 + 材料/地点。
 * 原则：只提取原文存在的信息；日期不唯一时不生成截止时间；低置信不产生强提醒。
 */
@Component
public class RuleExtractor {

    private static final Pattern FULL_DATE = Pattern.compile(
            "(\\d{4})\\s*[年./-]\\s*(\\d{1,2})\\s*[月./-]\\s*(\\d{1,2})\\s*[日号]?"
                    + "(?:\\s*(?:上午|下午|晚上|中午|凌晨)?\\s*(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2})?\\s*分?)?");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?"
                    + "(?:\\s*(?:上午|下午|晚上|中午|凌晨)?\\s*(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2})?\\s*分?)?");
    private static final Pattern RELATIVE_DAY = Pattern.compile("(今天|明天|后天|大后天)");
    private static final Pattern WEEK_DAY = Pattern.compile("(下周|本周|这周)([一二三四五六日天])");
    private static final Pattern MATERIAL = Pattern.compile(
            "(?:携带|准备|带上|需要提供|所需材料|材料)[：:]?\\s*([^。；;\\n]{2,60})");
    private static final Pattern LOCATION = Pattern.compile(
            "(?:地点|地址)[：:]\\s*([^\\n，。；]{2,40})|在\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20})(?:集合|举行|开展|参加|办理)");

    private static final Set<String> ACTION_WORDS = Set.of(
            "截止", "提交", "报名", "缴费", "支付", "确认", "打印", "面试", "考试",
            "开会", "领取", "归还", "办理", "上传", "签到", "递交", "填报", "预订", "预约", "参加");

    public CandidateExtraction extract(ExtractionRequest request) {
        String text = normalize(request.text());
        ZoneId zone = safeZone(request.timezone());
        ZonedDateTime received = (request.receivedAt() == null ? Instant.now() : request.receivedAt()).atZone(zone);
        List<String> warnings = new ArrayList<>();

        Set<LocalDateTime> dateTimes = new LinkedHashSet<>();
        List<String> dateQuotes = new ArrayList<>();
        boolean anyExplicitTime = false;

        Matcher full = FULL_DATE.matcher(text);
        while (full.find()) {
            LocalDate d = safeDate(Integer.parseInt(full.group(1)), Integer.parseInt(full.group(2)),
                    Integer.parseInt(full.group(3)));
            if (d != null) {
                LocalTime t = parseTime(full.group(4), full.group(5));
                anyExplicitTime |= t != null;
                dateTimes.add(LocalDateTime.of(d, t == null ? LocalTime.MIDNIGHT : t));
                dateQuotes.add(full.group());
            }
        }
        Matcher monthDay = MONTH_DAY.matcher(text);
        while (monthDay.find()) {
            int month = Integer.parseInt(monthDay.group(1));
            int day = Integer.parseInt(monthDay.group(2));
            int year = received.getYear();
            LocalDate d = safeDate(year, month, day);
            if (d != null && d.isBefore(received.toLocalDate())) {
                d = safeDate(year + 1, month, day);
            }
            if (d != null) {
                LocalTime t = parseTime(monthDay.group(3), monthDay.group(4));
                anyExplicitTime |= t != null;
                dateTimes.add(LocalDateTime.of(d, t == null ? LocalTime.MIDNIGHT : t));
                dateQuotes.add(monthDay.group());
            }
        }
        Matcher relative = RELATIVE_DAY.matcher(text);
        while (relative.find()) {
            int plus = switch (relative.group(1)) {
                case "今天" -> 0;
                case "明天" -> 1;
                case "后天" -> 2;
                default -> 3;
            };
            LocalDate d = received.toLocalDate().plusDays(plus);
            LocalTime t = nearbyTime(text, relative.end());
            anyExplicitTime |= t != null;
            dateTimes.add(LocalDateTime.of(d, t == null ? LocalTime.MIDNIGHT : t));
            dateQuotes.add(relative.group());
        }
        Matcher week = WEEK_DAY.matcher(text);
        while (week.find()) {
            LocalDate d = nextWeekday(received.toLocalDate(), week.group(2),
                    "下周".equals(week.group(1)));
            LocalTime t = nearbyTime(text, week.end());
            anyExplicitTime |= t != null;
            dateTimes.add(LocalDateTime.of(d, t == null ? LocalTime.MIDNIGHT : t));
            dateQuotes.add(week.group());
        }

        List<String> reasons = new ArrayList<>();
        String dueAt = null;
        String dueDate = null;
        String precision = "NONE";
        double dateConfidence = 0.2;
        if (dateTimes.size() == 1) {
            LocalDateTime dt = dateTimes.iterator().next();
            boolean hasTime = anyExplicitTime || dt.toLocalTime() != LocalTime.MIDNIGHT;
            dueAt = dt.atZone(zone).toInstant().toString();
            dueDate = dt.toLocalDate().toString();
            precision = hasTime ? "MINUTE" : "DATE";
            dateConfidence = hasTime ? 0.85 : 0.7;
            reasons.add(hasTime ? "EXPLICIT_DATETIME" : "EXPLICIT_DATE");
        } else if (dateTimes.size() > 1) {
            reasons.add("MULTIPLE_DATES");
            dateTimes.stream().sorted().limit(5)
                    .forEach(d -> reasons.add("DATE_OPTION:" + d.toString()));
            warnings.add("检测到多个候选日期，确认前不生成截止提醒");
            dateConfidence = 0.2;
        } else if (containsAction(text)) {
            reasons.add("NO_DATE");
            warnings.add("未找到明确日期，可创建无截止日期事项");
            dateConfidence = 0.1;
        }

        String title = deriveTitle(text);
        String summary = text.length() > 500 ? text.substring(0, 500) + "…" : text;
        List<String> materials = extractMaterials(text);
        String location = extractLocation(text);
        String nextAction = firstActionSentence(text);
        double confidence = 0.35;
        if (dateTimes.size() == 1) {
            confidence += anyExplicitTime ? 0.4 : 0.3;
        }
        if (!materials.isEmpty()) {
            confidence += 0.05;
        }
        if (containsAction(text)) {
            confidence += 0.1;
        }
        confidence = Math.min(0.95, confidence);

        Map<String, Double> fieldConfidence = new LinkedHashMap<>();
        fieldConfidence.put("title", title.isBlank() ? 0.1 : 0.7);
        fieldConfidence.put("due", dateConfidence);
        fieldConfidence.put("time", anyExplicitTime ? 0.8 : (dateTimes.size() == 1 ? 0.45 : 0.1));
        fieldConfidence.put("location", location == null ? 0.0 : 0.6);
        fieldConfidence.put("materials", materials.isEmpty() ? 0.0 : 0.6);

        CandidateExtraction.CandidateDraft draft = new CandidateExtraction.CandidateDraft(
                title.isBlank() ? "待确认事项" : title,
                summary,
                category(text),
                dueAt,
                dueDate,
                zone.getId(),
                precision,
                location,
                materials,
                nextAction,
                round(confidence),
                fieldConfidence,
                reasons
        );
        return new CandidateExtraction(List.of(draft), "rule-cn-v1", "rule-v1", "RULE", warnings);
    }

    static boolean containsAction(String text) {
        return ACTION_WORDS.stream().anyMatch(text::contains);
    }

    private static LocalTime nearbyTime(String text, int from) {
        String tail = text.substring(Math.min(from, text.length()),
                Math.min(text.length(), from + 18));
        Matcher m = Pattern.compile("(?:上午|下午|晚上|中午|凌晨)?\\s*(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2})?")
                .matcher(tail);
        if (m.find()) {
            return parseTime(m.group(1), m.group(2));
        }
        return null;
    }

    private static LocalTime parseTime(String hourRaw, String minuteRaw) {
        if (hourRaw == null) {
            return null;
        }
        int hour = Integer.parseInt(hourRaw);
        int minute = minuteRaw == null || minuteRaw.isBlank() ? 0 : Integer.parseInt(minuteRaw);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    private LocalDate nextWeekday(LocalDate base, String cnWeekday, boolean nextWeek) {
        DayOfWeek target = switch (cnWeekday) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
        LocalDate monday = base.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate week = nextWeek ? monday.plusWeeks(1) : monday;
        return week.with(java.time.temporal.TemporalAdjusters.nextOrSame(target));
    }

    private static LocalDate safeDate(int y, int m, int d) {
        try {
            return LocalDate.of(y, m, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ZoneId safeZone(String tz) {
        if (tz == null || tz.isBlank()) {
            return ZoneId.of("Asia/Shanghai");
        }
        try {
            return ZoneId.of(tz);
        } catch (java.time.DateTimeException e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private String deriveTitle(String text) {
        String firstLine = text.lines().filter(s -> !s.isBlank()).findFirst().orElse(text).trim();
        String candidate = firstLine;
        int colon = firstLine.indexOf('：');
        if (colon < 0) {
            colon = firstLine.indexOf(':');
        }
        if (colon > 0 && colon <= 24) {
            candidate = firstLine.substring(colon + 1).trim();
        }
        candidate = candidate.replaceAll("^[【\\[（(]+", "").replaceAll("[】\\]）)]+$", "").trim();
        String actionSentence = firstActionSentence(text);
        if (!actionSentence.isBlank() && (candidate.isBlank() || candidate.length() > 40)) {
            candidate = actionSentence;
        }
        if (candidate.length() > 60) {
            candidate = candidate.substring(0, 60) + "…";
        }
        return candidate.replaceAll("[。！!；;，,]+$", "").trim();
    }

    private String firstActionSentence(String text) {
        String[] parts = text.split("[。！!；;\\n]");
        for (String part : parts) {
            String p = part.trim();
            if (!p.isEmpty() && containsAction(p)) {
                return p.length() > 80 ? p.substring(0, 80) + "…" : p;
            }
        }
        return "";
    }

    private List<String> extractMaterials(String text) {
        Set<String> materials = new LinkedHashSet<>();
        Matcher m = MATERIAL.matcher(text);
        while (m.find() && materials.size() < 5) {
            String value = m.group(1).trim().replaceAll("[，,、]+$", "");
            if (!value.isEmpty()) {
                materials.add(value.length() > 40 ? value.substring(0, 40) : value);
            }
        }
        return new ArrayList<>(materials);
    }

    private String extractLocation(String text) {
        Matcher m = LOCATION.matcher(text);
        if (m.find()) {
            String value = m.group(1) != null ? m.group(1) : m.group(2);
            return value == null ? null : value.trim();
        }
        return null;
    }

    private String category(String text) {
        if (text.contains("报名")) {
            return "REGISTRATION";
        }
        if (text.contains("缴费") || text.contains("支付") || text.contains("付款")) {
            return "PAYMENT";
        }
        if (text.contains("截止") || text.contains("提交") || text.contains("上传")) {
            return "SUBMISSION";
        }
        if (text.contains("开会") || text.contains("会议") || text.contains("活动")) {
            return "EVENT";
        }
        return "TASK";
    }

    private static double round(double v) {
        return Math.round(v * 10000d) / 10000d;
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\u0000', ' ').trim();
    }
}
